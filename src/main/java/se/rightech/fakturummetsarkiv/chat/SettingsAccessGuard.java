package se.rightech.fakturummetsarkiv.chat;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Component
public class SettingsAccessGuard {

    static final String WRITE_HEADER = "X-Requested-With";
    static final String WRITE_HEADER_VALUE = "FakturummetsArkiv";

    public void requireWriteAccess(HttpServletRequest request) {
        if (request == null || !isLoopback(request.getRemoteAddr())) {
            throw new SettingsAccessDeniedException(
                    "Inställningar kan bara ändras från serverns egen dator");
        }
        if (!WRITE_HEADER_VALUE.equals(request.getHeader(WRITE_HEADER))) {
            throw new SettingsAccessDeniedException(
                    "Saknar " + WRITE_HEADER + ": " + WRITE_HEADER_VALUE);
        }
    }

    static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }
}
