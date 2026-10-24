package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsAccessGuardTest {

    private final SettingsAccessGuard guard = new SettingsAccessGuard();

    @Test
    void allowsLoopbackWithWriteHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Requested-With", "FakturummetsArkiv");

        assertThatCode(() -> guard.requireWriteAccess(request)).doesNotThrowAnyException();
        assertThatCode(() -> guard.requireWriteAccess(request("::1"))).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonLoopbackAndMissingHeader() {
        assertThatThrownBy(() -> guard.requireWriteAccess(request("192.168.1.10")))
                .isInstanceOf(SettingsAccessDeniedException.class)
                .hasMessageContaining("serverns egen dator");

        MockHttpServletRequest loopback = new MockHttpServletRequest();
        loopback.setRemoteAddr("127.0.0.1");
        assertThatThrownBy(() -> guard.requireWriteAccess(loopback))
                .isInstanceOf(SettingsAccessDeniedException.class)
                .hasMessageContaining("X-Requested-With");
    }

    private static MockHttpServletRequest request(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader("X-Requested-With", "FakturummetsArkiv");
        return request;
    }
}
