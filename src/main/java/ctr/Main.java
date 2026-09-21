package ctr;

import java.net.InetSocketAddress;
import java.nio.file.Path;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5220;
        for (int i = 0; i < args.length; i++) {
            if ("--host".equals(args[i]) && i + 1 < args.length) {
                host = args[++i];
            } else if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                System.out.println("Usage: ctr.Main --host 127.0.0.1 --port 5220");
                return;
            } else {
                throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }
        Path dataDirectory = Path.of(System.getProperty("ctr.data", "data"));
        RegistryService registryService = new RegistryService(dataDirectory);
        JobService jobService = new JobService(dataDirectory, registryService);
        WebServer server = new WebServer(host, port, registryService, jobService);
        server.start();
        InetSocketAddress address = server.address();
        System.out.println("坐标变换注册站正在运行: http://" + address.getHostString() + ":" + address.getPort());
    }
}
