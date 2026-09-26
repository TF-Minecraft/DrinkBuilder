package net.tfminecraft.tfmcweb.api;

/** Test fixture for TFMCWeb's reflection-only contract; never contacts a server. */
public final class ProvinceSystemGateway {
    public static Object response;
    public static RuntimeException failure;
    public static Object[] request;
    public static Object request(String method, String path, String body) {
        request = new Object[]{method, path, body};
        return respond();
    }
    public static Object requestBytes(String method, String path, byte[] body, String contentType) {
        request = new Object[]{method, path, body, contentType};
        return respond();
    }
    public static Object download(String path) {
        request = new Object[]{path};
        return respond();
    }
    private static Object respond() {
        if (failure != null) throw failure;
        return response;
    }
    public static final class Response {
        public Object ok, body, error;
        public byte[] data;
        public Response(Object ok, Object body, Object error, byte[] data) {
            this.ok = ok; this.body = body; this.error = error; this.data = data;
        }
    }
}
