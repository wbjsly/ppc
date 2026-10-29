package com.erp.system;

import com.erp.service.system.PrivilegedRoles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 特权清单源码扫描守护（add-role-permission-console design D3）：
 * 扫描 src/main/java 全部源文件，提取 ApprovalNodeSpec.sign("ROLE_*") 字面量，
 * 断言 ⊆ PrivilegedRoles.ALL——新增审批角色忘进特权清单 → 本测试红。
 * 纯文件扫描，不起 Spring 上下文。
 */
class PrivilegedRolesScanTest {

    private static final Pattern SIGN_LITERAL =
            Pattern.compile("ApprovalNodeSpec\\.sign\\(\\s*\"(ROLE_[A-Z0-9_]+)\"");

    private static final Path[] SOURCE_ROOTS = {
            Paths.get("src/main/java"),
            Paths.get("backend/src/main/java")
    };

    @Test
    void allApprovalNodeRolesArePrivileged() throws IOException {
        Path root = Stream.of(SOURCE_ROOTS)
                .filter(Files::isDirectory)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "找不到源码根目录（surefire cwd=" + Paths.get("").toAbsolutePath() + "）"));

        Set<String> found = new HashSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String src = Files.readString(p);
                    Matcher m = SIGN_LITERAL.matcher(src);
                    while (m.find()) {
                        found.add(m.group(1));
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("读取源文件失败: " + p, e);
                }
            });
        }

        // 扫描自检：路径或正则失效时 found 为空，绝不能静默通过
        assertFalse(found.isEmpty(), "源码扫描结果为空——扫描路径/正则失效，守护已失效");

        Set<String> missing = new HashSet<>(found);
        missing.removeAll(PrivilegedRoles.ALL);
        assertTrue(missing.isEmpty(),
                "以下审批节点角色未加入 PrivilegedRoles 特权清单（岗位基线 L1 硬阻断会漏拦）: "
                        + missing);
    }
}
