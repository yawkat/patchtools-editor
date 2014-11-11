package at.yawk.patchtools.editor;

import com.strobel.decompiler.ITextOutput;
import java.util.ListIterator;
import java.util.function.Consumer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.Printer;
import uk.co.thinkofdeath.patchtools.instruction.Instruction;

/**
 * @author yawkat
 */
public class BytecodeMarkup {
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
            output.writeLine(".class %s", name);
            output.indent();
            if (superName != null && !superName.equals(Type.getType(Object.class).getInternalName())) {
                output.writeLine(".super %s", superName);
            }
            for (String interfac : interfaces) {
                output.writeLine(".interface %s", interfac);
            }
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
            visitMember();
            output.write(".field %s %s", name, desc);
            if ((access & Opcodes.ACC_STATIC) != 0) { output.write(" static"); }
            if ((access & Opcodes.ACC_PRIVATE) != 0) { output.write(" private"); }
            if (value instanceof String) {
                output.write(" \"" + value + "\"");
            } else if (value != null) {
                output.write(' ' + String.valueOf(value));
            }
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
            output.write(".method %s %s", name, desc);
            if ((access & Opcodes.ACC_STATIC) != 0) { output.write(" static"); }
            if ((access & Opcodes.ACC_PROTECTED) != 0) { output.write(" protected"); }
            if ((access & Opcodes.ACC_PRIVATE) != 0) { output.write(" private"); }
            output.writeLine();
            output.indent();
            return new PrintingMethodVisitor2(new MethodNode(Opcodes.ASM5, access, name, desc, signature, exceptions));
        }

        @Override
        public void visitEnd() {
            output.unindent();
            output.writeLine(".end-class");
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
                    boolean printed = Instruction.print(builder, method, node);
                    if (!printed) {
                        int opcode = node.getOpcode() & 0xff;
                        if (opcode > Printer.OPCODES.length) { continue; }
                        builder.append(Printer.OPCODES[opcode]);
                    }
                    output.writeLine(builder.toString().replace("\n", "\\n"));
                }
                output.unindent();
                output.writeLine(".end-method");
            }
        }
    }
}
