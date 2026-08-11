package com.scanner;

import java.io.IOException;
import java.net.*;

public final class PortChecker {

    public static boolean isPortOpen(String ip, int port, double timeoutSec) {
        return isPortOpen(ip, port, timeoutSec, null);
    }

    public static boolean isPortOpen(String ip, int port, double timeoutSec, Proxy proxy) {
        try (Socket sock = proxy == null ? new Socket() : new Socket(proxy)) {
            sock.connect(new InetSocketAddress(ip, port), (int)(timeoutSec * 1000));
            return true;
        } catch (SocketTimeoutException e) {
            NetworkMonitor.getInstance().recordTimeout();
            return false;
        } catch (BindException | NoRouteToHostException e) {
            NetworkMonitor.getInstance().recordError();
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}