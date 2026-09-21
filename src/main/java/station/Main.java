package station;

import java.nio.file.Path;
import station.server.WebServer;
import station.store.Registry;

/** 入口：--host --port --data 指定监听地址与持久化文件。 */
public final class Main {
    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5220;
        String data = "data/registry.json";
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--host": host = args[++i]; break;
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--data": data = args[++i]; break;
                default: break;
            }
        }
        Registry registry = Registry.load(Path.of(data));
        WebServer server = new WebServer(registry, host, port);
        server.start();
        System.out.println("坐标变换注册站已启动: http://" + host + ":" + port
                + " （注册表版本 " + registry.version() + "，数据文件 " + data + "）");
        Thread.currentThread().join();
    }
}
