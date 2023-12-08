import java.security.Permission;

public class MySecurityManager extends SecurityManager {
    @Override
    public void checkPermission(Permission perm) {
//        super.checkPermission(perm);
    }

    @Override
    public void checkExec(String cmd) {
        throw new SecurityException("Exec 权限异常:" + cmd);
    }

    @Override
    public void checkRead(String file) {
//        throw new SecurityException("Read 权限异常:" + file);
    }

    @Override
    public void checkWrite(String file) {
        throw new SecurityException("Write 权限异常:" + file);
    }

    @Override
    public void checkDelete(String file) {
        throw new SecurityException("Delete 权限异常:" + file);
    }

    @Override
    public void checkConnect(String host, int port) {
        throw new SecurityException("Connect 权限异常:" + host + ":" + port);
    }
}
