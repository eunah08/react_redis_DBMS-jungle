package minidbms.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ServerOptionsTest {

    @Test
    void 옵션을_읽는다() {
        ServerOptions options = ServerOptions.parse(new String[] {
                "--host", "0.0.0.0", "--port", "9000", "--workers", "4", "--queue", "16", "--data-dir", "mydata"});

        assertEquals(new ServerOptions("0.0.0.0", 9000, 4, 16, Path.of("mydata")), options);
    }

    @Test
    void 아무것도_없으면_기본값이다() {
        assertEquals(ServerOptions.defaults(), ServerOptions.parse(new String[] {}));
    }

    @Test
    void 잘못된_옵션은_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> ServerOptions.parse(new String[] {"--port"}));
        assertThrows(IllegalArgumentException.class, () -> ServerOptions.parse(new String[] {"--port", "abc"}));
        assertThrows(IllegalArgumentException.class, () -> ServerOptions.parse(new String[] {"--port", "70000"}));
        assertThrows(IllegalArgumentException.class, () -> ServerOptions.parse(new String[] {"--workers", "0"}));
        assertThrows(IllegalArgumentException.class, () -> ServerOptions.parse(new String[] {"--queue", "0"}));
        assertThrows(IllegalArgumentException.class, () -> ServerOptions.parse(new String[] {"--nope", "1"}));
    }
}
