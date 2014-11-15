package at.yawk.patchtools.editor;

import com.google.common.collect.ImmutableMap;
import com.strobel.decompiler.ITextOutput;
import java.util.Arrays;
import java.util.ListIterator;
import java.util.Map;
import java.util.function.Consumer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.Printer;
import uk.co.thinkofdeath.patchtools.instruction.Instruction;
import uk.co.thinkofdeath.patchtools.instruction.Instructions;

/**
 * @author yawkat
 */
public class BytecodeMarkup {
    private static final Map<Integer, String> ACCESS =
            ImmutableMap.<Integer, String>builder()
                    .put(Opcodes.ACC_PRIVATE, "private")
                    .put(Opcodes.ACC_PUBLIC, "public")
                    .put(Opcodes.ACC_PROTECTED, "protected")
                    .put(Opcodes.ACC_STATIC, "static")
                    .put(Opcodes.ACC_SYNCHRONIZED, "synchronized")
                    .put(Opcodes.ACC_FINAL, "final")
                    .build();

    private static void writeModifiers(ITextOutput ito, int modifiers, int... exclude) {
        Arrays.sort(exclude);
        ACCESS.forEach((k, v) -> {
            if (Arrays.binarySearch(exclude, k) == -1 && (modifiers & k) != 0) {
                ito.write(v);
                ito.write(' ');
            }
        });
    }

    public void write(Consumer<ClassVisitor> node, ITextOutput output) {
        node.accept(new PrintingClassVisitor(output));
    }

    private static class PrintingClassVisitor extends ClassVisitor {
        private final ITextOutput output;
        private boolean firstMember = true;

        public PrintingClassVisitor(ITextOutput output) {
            super(Opcodes.ASM5);
            this.output = output;
        }

        @Override
        public void visit(
                int version, int access, String name, String signature, String superName, String[] interfaces) {
            super.visit(version, access, name, signature, superName, interfaces);
            writeModifiers(output, access, Opcodes.ACC_SYNCHRONIZED);
            output.write("class ");
            output.write(name);
            if (superName != null && !superName.equals(Type.getType(Object.class).getInternalName())) {
                output.write(" extends %s", superName);
            }
            for (int i = 0; i < interfaces.length; i++) {
                if (i == 0) {
                    output.write(" implements");
                } else {
                    output.write(", ");
                }
                output.write(" %s", interfaces[i]);
            }
            output.write(" {");
            output.writeLine();
            output.indent();
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
            visitMember();
            writeModifiers(output, access);
            output.write(Type.getType(desc).getClassName());
            output.write(' ');
            output.write(name);
            if (value instanceof String) {
                output.write(" = \"" + ((String) value).replace("\"", "\\\"") + "\"");
            } else if (value != null) {
                output.write(" = " + value);
            }
            output.write(';');
            output.writeLine();
            return super.visitField(access, name, desc, signature, value);
        }

        private void visitMember() {
            if (!firstMember) {
                output.writeLine();
            } else {
                firstMember = false;
            }
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            visitMember();

            writeModifiers(output, access);
            output.write(Type.getReturnType(desc).getClassName());
            output.write(' ');
            output.write(name);
            output.write('(');
            Type[] argumentTypes = Type.getArgumentTypes(desc);
            for (int i = 0; i < argumentTypes.length; i++) {
                if (i > 0) { output.write(", "); }
                output.write(argumentTypes[i].getClassName());
                output.write(' ');
                output.write((char) ('a' + i)); // lets hope nobody uses > 26 args
            }
            output.write(") {");
            output.writeLine();
            output.indent();
            return new PrintingMethodVisitor2(new MethodNode(Opcodes.ASM5, access, name, desc, signature, exceptions));
        }

        @Override
        public void visitEnd() {
            output.unindent();
            output.writeLine("}");
        }

        private class PrintingMethodVisitor2 extends MethodVisitor {
            private final MethodNode method;

            public PrintingMethodVisitor2(MethodNode node) {
                super(Opcodes.ASM5, node);
                this.method = node;
            }

            @Override
            public void visitEnd() {
                super.visitEnd();

                ListIterator<AbstractInsnNode> iterator = method.instructions.iterator();
                while (iterator.hasNext()) {
                    AbstractInsnNode node = iterator.next();
                    StringBuilder builder = new StringBuilder(".");
                    boolean printed = Instructions.print(builder, method, node);
                    if (!printed) {
                        int opcode = node.getOpcode() & 0xff;
                        if (opcode > Printer.OPCODES.length) { continue; }
                        builder.append(Printer.OPCODES[opcode]);
                    }
                    output.writeLine(builder.toString().replace("\n", "\\n"));
                }
                output.unindent();
                output.writeLine("}");
            }
        }
    }
}
