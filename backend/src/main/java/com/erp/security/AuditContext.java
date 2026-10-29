package com.erp.security;

/**
 * 审计请求上下文（add-audit-log-console D3）：AuditContextFilter 在请求进入时存 IP/终端，
 * 请求结束 finally 清理（防 ThreadLocal 泄漏）。MetaObjectHandler / AuditLogWriter 读取回填。
 */
public final class AuditContext {

    private static final ThreadLocal<String> IP = new ThreadLocal<>();
    private static final ThreadLocal<String> TERMINAL = new ThreadLocal<>();

    private AuditContext() {
    }

    public static void set(String ip, String terminal) {
        IP.set(ip);
        TERMINAL.set(terminal);
    }

    public static String getIp() {
        return IP.get();
    }

    public static String getTerminal() {
        return TERMINAL.get();
    }

    public static void clear() {
        IP.remove();
        TERMINAL.remove();
    }
}
