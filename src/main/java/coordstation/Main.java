package coordstation;

import coordstation.registry.Registry;
import coordstation.server.HttpApi;
import coordstation.server.Store;

import java.nio.file.Path;

public final class Main {
    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5220;
        String data = "data/registry-store.json";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host": host = args[++i]; break;
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--data": data = args[++i]; break;
                default:
                    System.err.println("unknown argument: " + args[i]);
                    System.exit(2);
            }
        }
        Registry registry = new Registry();
        Store store = new Store(Path.of(data));
        if (store.exists()) {
            store.loadInto(registry);
            System.out.println("loaded store " + data + " at version " + registry.version());
        }
        HttpApi api = new HttpApi(registry, store);
        api.start(host, port);
        System.out.println("坐标变换注册站 listening on http://" + host + ":" + api.port());
        Thread.currentThread().join();
    }
}
