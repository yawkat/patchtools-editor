package at.yawk.patchtools.editor;

import java.util.concurrent.ThreadFactory;
import org.jetbrains.annotations.NotNull;

/**
* @author yawkat
*/
class DaemonThreadFactory implements ThreadFactory {
    @Override
    public Thread newThread(@NotNull Runnable r) {
        Thread thread = new Thread(r);
        thread.setDaemon(true);
        return thread;
    }
}
