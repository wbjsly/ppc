package com.erp.service.impl.i18n;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.i18n.I18nKeyDao;
import com.erp.entity.i18n.I18nKey;
import com.erp.service.i18n.I18nKeyService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 翻译键值实现（add-i18n-l10n，BR-5.7-03 占位符校验）。
 * 占位符口径：提取 {xxx} 集合，数量/名称/顺序与源语不一致 → 拒绝导入并标注行号。
 */
@Slf4j
@Service
public class I18nKeyServiceImpl implements I18nKeyService {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");
    private static final String SRC = "zh-CN";

    private final I18nKeyDao keyDao;
    private final JdbcTemplate jdbc;

    public I18nKeyServiceImpl(I18nKeyDao keyDao, JdbcTemplate jdbc) {
        this.keyDao = keyDao;
        this.jdbc = jdbc;
    }

    @Override
    public List<Map<String, Object>> list(String locale, String module, String priority, String keyword,
                                          int page, int size) {
        StringBuilder sql = new StringBuilder(
                "SELECT k.ID AS id, k.KEY_NAME AS keyName, k.MODULE AS module, k.PRIORITY AS priority, "
                        + "k.CONTEXT_NOTE AS contextNote, k.SOURCE_TEXT AS sourceText, "
                        + "t.TARGET_TEXT AS targetText, k.LOCALE AS locale, k.PACK_NO AS packNo "
                        + "FROM erp_i18n_key k "
                        + "LEFT JOIN erp_i18n_key t ON t.KEY_NAME=k.KEY_NAME AND t.LOCALE=? AND t.PACK_NO=k.PACK_NO "
                        + "AND t.DEL_FLAG='0' "
                        + "WHERE k.DEL_FLAG='0' AND k.LOCALE=?");
        List<Object> args = new ArrayList<>();
        String target = locale == null || locale.isBlank() ? "en-US" : locale.trim();
        args.add(target);
        args.add(SRC);
        if (module != null && !module.isBlank()) {
            sql.append(" AND k.MODULE=?");
            args.add(module.trim());
        }
        if (priority != null && !priority.isBlank()) {
            sql.append(" AND k.PRIORITY=?");
            args.add(priority.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (k.KEY_NAME LIKE ? OR k.SOURCE_TEXT LIKE ?)");
            args.add("%" + keyword.trim() + "%");
            args.add("%" + keyword.trim() + "%");
        }
        sql.append(" ORDER BY k.MODULE, k.PRIORITY, k.KEY_NAME LIMIT ? OFFSET ?");
        int lim = Math.min(Math.max(size, 1), 200);
        args.add(lim);
        args.add(Math.max(page - 1, 0) * lim);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(Map<String, Object> body) {
        String keyName = str(body.get("keyName"));
        String locale = str(body.getOrDefault("locale", SRC));
        if (keyName == null || keyName.isBlank()) {
            throw new ServiceException(422, "键名必填");
        }
        I18nKey row = new I18nKey();
        row.setKeyName(keyName.trim());
        row.setModule(str(body.get("module")));
        row.setPriority(str(body.getOrDefault("priority", "P1")));
        row.setContextNote(str(body.get("contextNote")));
        row.setSourceText(str(body.get("sourceText")));
        row.setTargetText(str(body.get("targetText")));
        row.setLocale(locale);
        row.setPackNo(str(body.getOrDefault("packNo", "0.0.0")));
        row.setUpdateBy(operator());
        row.setUpdateDate(LocalDateTime.now());

        if (SRC.equals(locale)) {
            if (row.getSourceText() == null || row.getSourceText().isBlank()) {
                throw new ServiceException(422, "源语文本必填");
            }
            // 源语：同键名已存在 → 更新
            I18nKey exist = keyDao.selectOne(new LambdaQueryWrapper<I18nKey>()
                    .eq(I18nKey::getKeyName, row.getKeyName())
                    .eq(I18nKey::getLocale, SRC)
                    .eq(I18nKey::getPackNo, row.getPackNo())
                    .last("LIMIT 1"));
            if (exist != null) {
                row.setId(exist.getId());
                keyDao.updateById(row);
            } else {
                row.setCreateBy(operator());
                row.setCreateDate(LocalDateTime.now());
                keyDao.insert(row);
            }
        } else {
            // 目标语：占位符与源语一致性（保存路径同样校验，BR-5.7-03 语义）
            I18nKey src = keyDao.selectOne(new LambdaQueryWrapper<I18nKey>()
                    .eq(I18nKey::getKeyName, row.getKeyName())
                    .eq(I18nKey::getLocale, SRC)
                    .last("LIMIT 1"));
            if (src == null) {
                throw new ServiceException(422, "源语键不存在：" + row.getKeyName());
            }
            if (row.getTargetText() != null && !row.getTargetText().isBlank()
                    && !placeholdersEqual(src.getSourceText(), row.getTargetText())) {
                throw new ServiceException(422, "占位符与源语不一致（BR-5.7-03）");
            }
            I18nKey exist = keyDao.selectOne(new LambdaQueryWrapper<I18nKey>()
                    .eq(I18nKey::getKeyName, row.getKeyName())
                    .eq(I18nKey::getLocale, locale)
                    .eq(I18nKey::getPackNo, row.getPackNo())
                    .last("LIMIT 1"));
            if (exist != null) {
                row.setId(exist.getId());
                keyDao.updateById(row);
            } else {
                row.setCreateBy(operator());
                row.setCreateDate(LocalDateTime.now());
                keyDao.insert(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.getId());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        I18nKey k = keyDao.selectById(id);
        if (k == null) {
            throw new ServiceException(404, "键不存在");
        }
        if (SRC.equals(k.getLocale())) {
            // 源语删除 → 级联目标语
            jdbc.update("UPDATE erp_i18n_key SET DEL_FLAG='1', UPDATE_DATE=NOW() WHERE KEY_NAME=?",
                    k.getKeyName());
        } else {
            jdbc.update("UPDATE erp_i18n_key SET DEL_FLAG='1', UPDATE_DATE=NOW() WHERE ID=?", id);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int importCsv(String locale, String csvText) {
        if (csvText == null || csvText.isBlank()) {
            throw new ServiceException(422, "导入内容为空");
        }
        String[] lines = csvText.split("\r?\n");
        List<String[]> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i].trim();
            if (ln.isEmpty() || (i == 0 && ln.toLowerCase().startsWith("key_name"))) {
                continue;
            }
            String[] parts = ln.split(",", 2);
            if (parts.length < 2) {
                errors.add("第 " + (i + 1) + " 行：列数不足（key_name,target_text）");
                continue;
            }
            rows.add(new String[]{parts[0].trim(), parts[1].trim()});
        }
        // 占位符校验（BR-5.7-03）：任一行不匹配 → 拒绝整个文件
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            I18nKey src = keyDao.selectOne(new LambdaQueryWrapper<I18nKey>()
                    .eq(I18nKey::getKeyName, r[0])
                    .eq(I18nKey::getLocale, SRC)
                    .last("LIMIT 1"));
            if (src == null) {
                errors.add("第 " + (i + 2) + " 行：源语键不存在 " + r[0]);
                continue;
            }
            if (!placeholdersEqual(src.getSourceText(), r[1])) {
                errors.add("第 " + (i + 2) + " 行：占位符与源语不一致（键 " + r[0] + "）");
            }
        }
        if (!errors.isEmpty()) {
            throw new ServiceException(422, "导入被拒绝（BR-5.7-03）：" + String.join("；", errors));
        }
        int n = 0;
        for (String[] r : rows) {
            I18nKey src = keyDao.selectOne(new LambdaQueryWrapper<I18nKey>()
                    .eq(I18nKey::getKeyName, r[0])
                    .eq(I18nKey::getLocale, SRC)
                    .last("LIMIT 1"));
            I18nKey exist = keyDao.selectOne(new LambdaQueryWrapper<I18nKey>()
                    .eq(I18nKey::getKeyName, r[0])
                    .eq(I18nKey::getLocale, locale)
                    .eq(I18nKey::getPackNo, src.getPackNo())
                    .last("LIMIT 1"));
            I18nKey row = exist != null ? exist : new I18nKey();
            row.setKeyName(r[0]);
            row.setModule(src.getModule());
            row.setPriority(src.getPriority());
            row.setSourceText(src.getSourceText());
            row.setTargetText(r[1]);
            row.setLocale(locale);
            row.setPackNo(src.getPackNo());
            row.setUpdateBy(operator());
            row.setUpdateDate(LocalDateTime.now());
            if (exist == null) {
                row.setCreateBy(operator());
                row.setCreateDate(LocalDateTime.now());
                keyDao.insert(row);
            } else {
                keyDao.updateById(row);
            }
            n++;
        }
        return n;
    }

    @Override
    public String exportCsv(String locale, String module) {
        String target = locale == null || locale.isBlank() ? "en-US" : locale;
        StringBuilder sb = new StringBuilder("key_name,module,priority,source_text,target_text\n");
        String sql = "SELECT k.KEY_NAME, k.MODULE, k.PRIORITY, k.SOURCE_TEXT, "
                + "COALESCE(t.TARGET_TEXT,'') FROM erp_i18n_key k "
                + "LEFT JOIN erp_i18n_key t ON t.KEY_NAME=k.KEY_NAME AND t.LOCALE=? AND t.PACK_NO=k.PACK_NO AND t.DEL_FLAG='0' "
                + "WHERE k.DEL_FLAG='0' AND k.LOCALE=? AND (k.MODULE=? OR ? IS NULL OR ?='') "
                + "ORDER BY k.MODULE, k.KEY_NAME";
        jdbc.query(sql, rs -> {
            sb.append(esc(rs.getString(1))).append(',').append(esc(rs.getString(2))).append(',')
                    .append(esc(rs.getString(3))).append(',').append(esc(rs.getString(4))).append(',')
                    .append(esc(rs.getString(5))).append('\n');
        }, target, SRC, module, module, module);
        return sb.toString();
    }

    /** 占位符集合相等（数量/名称/顺序，BR-5.7-03） */
    static boolean placeholdersEqual(String source, String target) {
        return extract(source).equals(extract(target));
    }

    private static List<String> extract(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private String operator() {
        try {
            String u = SecurityUtils.getCurrentUserId();
            return u == null ? "system" : u;
        } catch (Exception e) {
            return "system";
        }
    }
}
