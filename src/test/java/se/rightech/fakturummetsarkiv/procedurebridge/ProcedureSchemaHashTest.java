package se.rightech.fakturummetsarkiv.procedurebridge;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;

class ProcedureSchemaHashTest {

    private static final String EXPECTED_SHA256 = "7ffe21af4a325d864778628e1a191140354d1aa1f89ae32b991171c14d314a19";

    @Test
    void schemaFileIsUnchanged() throws Exception {
        ClassPathResource res = new ClassPathResource("procedurebridge/procedure-schema.json");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = res.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) >= 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        assertThat(sb.toString()).isEqualTo(EXPECTED_SHA256);
    }
}
