package com.quant.platform.dataperm.interceptor;

import cn.dev33.satoken.exception.NotWebContextException;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.quant.platform.common.enums.ResourceType;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 数据权限查询隔离（方案C 查询零侵入）。
 *
 * 为什么不用 MyBatis-Plus 自带的 DataPermissionInterceptor：
 *   该拦截器依赖 jsqlparser 的 net.sf.jsqlparser.statement.select.SelectBody，而 SelectBody 在 jsqlparser 4.x 起已移除，
 *   MP 3.5.10.1 的 mybatis-plus-jsqlparser 模块却仍按 3.x 编译，导致运行期 NoClassDefFoundError 且无法用任何 4.x/5.x 版本解决。
 * 因此这里自己实现 InnerInterceptor，在 beforeQuery 直接改写 SQL 字符串，注入可见性条件，不依赖 jsqlparser。
 *
 * 可见性规则（能看到 = 自己建的 OR 公开 OR 部门可见(本部门+子部门向下) OR 被显式授权(用户/部门/角色)）：
 *   id IN (SELECT resource_id FROM resource_meta ...) OR id IN (SELECT resource_id FROM resource_share ...)
 * admin 角色跳过（看全部）。
 *
 * 安全说明：注入的 uid/deptPath/roleIds 全部来自 Sa-Token 登录会话（可信系统值，非前端入参），
 * 但仍做严格格式校验后再内联为 SQL 字面量，防止会话数据被篡改后成为注入点。
 */
@Component
public class ResourcePermissionInnerInterceptor implements InnerInterceptor {

    // 资源类型编码：枚举值，只允许大写字母、数字、下划线
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]*$");
    // 角色 ID 串：只允许 "1" 或 "1,2,3"
    private static final Pattern ROLE_IDS_PATTERN = Pattern.compile("^[0-9]+(,[0-9]+)*$");
    // 部门路径：如 /1 或 /1/10/30
    private static final Pattern DEPT_PATH_PATTERN = Pattern.compile("^(/[0-9]+)+$");

    private static final Map<String, ResourceType> MAPPER_MAP = new HashMap<>();
    static {
        MAPPER_MAP.put("StrategyDefinitionMapper", ResourceType.STRATEGY);
        MAPPER_MAP.put("FactorDefinitionMapper", ResourceType.FACTOR);
        MAPPER_MAP.put("BacktestTaskMapper", ResourceType.BACKTEST);
        MAPPER_MAP.put("PaperTradingMapper", ResourceType.PAPER_TRADING);
    }

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter,
                            RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
        ResourceType type = resolveType(ms.getId());
        if (type == null) {
            return;
        }
        // 非 web 上下文（启动初始化 / @Scheduled 后台线程 / 命令行任务）没有登录态，
        // Sa-Token 会抛 NotWebContextException；此时不做数据权限过滤（内部维护任务需全量可见）。
        try {
            if (!StpUtil.isLogin() || isAdmin()) {
                return;
            }
        } catch (NotWebContextException e) {
            return;
        }
        Long uid;
        String deptPath;
        String roleIds;
        String code = type.getCode();
        try {
            uid = StpUtil.getLoginIdAsLong();
            deptPath = currentDeptPath();
            roleIds = currentRoleIds();
        } catch (NotWebContextException e) {
            return;
        }

        // 严格校验后再拼入 SQL
        assertSafeCode(code);
        assertSafeDeptPath(deptPath);
        assertSafeRoleIds(roleIds);

        String cond = "id IN ("
                + "SELECT resource_id FROM resource_meta m WHERE m.resource_type='" + code + "'"
                + " AND (m.owner_id=" + uid
                + " OR m.visibility='PUBLIC'"
                + " OR (m.visibility='DEPT' AND EXISTS(SELECT 1 FROM sys_department d WHERE d.id=m.owner_dept_id AND ('"
                + escapeSql(deptPath) + "' = d.dept_path OR '" + escapeSql(deptPath) + "' LIKE CONCAT(d.dept_path,'/%'))))))"
                + " OR id IN ("
                + "SELECT resource_id FROM resource_share s WHERE s.resource_type='" + code + "'"
                + " AND ((s.grantee_type='USER' AND s.grantee_id=" + uid + ")"
                + " OR (s.grantee_type='DEPT' AND EXISTS(SELECT 1 FROM sys_department d WHERE d.id=s.grantee_id AND ('"
                + escapeSql(deptPath) + "' = d.dept_path OR '" + escapeSql(deptPath) + "' LIKE CONCAT(d.dept_path,'/%'))))"
                + " OR (s.grantee_type='ROLE' AND s.grantee_id IN (" + roleIds + "))))";

        String sql = boundSql.getSql();
        String newSql = injectWhere(sql, cond);
        MetaObject metaObject = SystemMetaObject.forObject(boundSql);
        metaObject.setValue("sql", newSql);
    }

    /** 把条件注入到 WHERE 子句：已有 WHERE 则 AND 追加，否则新建 WHERE；定位在 ORDER BY/LIMIT/GROUP BY 之前。 */
    private String injectWhere(String sql, String cond) {
        // 注入时把可见性条件定位在 ORDER BY / LIMIT / GROUP BY 之前（这些子句保留在 tail：
        // 数据查询保持原排序；分页 count 由 PaginationInnerInterceptor 处理。注入后的 SQL 括号已平衡，
        // 因此 count 子查询 SELECT COUNT(*) FROM (<sql>) TOTAL 合法（jsqlparser 解析或回退包装均可）。
        int idx = sql.length();
        for (String clause : new String[] { " ORDER BY ", " LIMIT ", " GROUP BY " }) {
            int p = sql.toUpperCase().indexOf(clause);
            if (p >= 0 && p < idx) {
                idx = p;
            }
        }
        String head = sql.substring(0, idx);
        String tail = sql.substring(idx);
        // 必须整体加括号：cond 内部含 OR，若直接 "AND cond" 会变成 "a AND x OR y"，
        // 因 AND 优先级高于 OR，等价于 "(a AND x) OR y"，y 会脱离原 WHERE 约束导致误匹配。
        if (head.toUpperCase().contains(" WHERE ")) {
            return head + " AND (" + cond + ")" + tail;
        }
        return head + " WHERE (" + cond + ")" + tail;
    }

    private ResourceType resolveType(String mappedStatementId) {
        if (mappedStatementId == null) {
            return null;
        }
        int lastDot = mappedStatementId.lastIndexOf('.');
        String mapperPart = lastDot >= 0 ? mappedStatementId.substring(0, lastDot) : mappedStatementId;
        int dot2 = mapperPart.lastIndexOf('.');
        String simpleName = dot2 >= 0 ? mapperPart.substring(dot2 + 1) : mapperPart;
        return MAPPER_MAP.get(simpleName);
    }

    private boolean isAdmin() {
        if (!StpUtil.isLogin()) {
            return false;
        }
        List<String> roles = StpUtil.getRoleList();
        return roles != null && roles.contains("ADMIN");
    }

    private String currentDeptPath() {
        Object p = StpUtil.getSession().get("deptPath");
        return p != null ? p.toString() : "/1";
    }

    private String currentRoleIds() {
        Object r = StpUtil.getSession().get("roleIds");
        if (r == null) {
            return "0";
        }
        String s = r.toString().trim();
        return s.isEmpty() ? "0" : s;
    }

    private String escapeSql(String v) {
        if (v == null) {
            return "";
        }
        return v.replace("'", "''");
    }

    private static void assertSafeCode(String code) {
        if (code == null || !CODE_PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException("非法的资源类型编码: " + code);
        }
    }

    private static void assertSafeDeptPath(String deptPath) {
        if (deptPath == null || !DEPT_PATH_PATTERN.matcher(deptPath).matches()) {
            throw new IllegalArgumentException("非法的部门路径: " + deptPath);
        }
    }

    private static void assertSafeRoleIds(String roleIds) {
        if (roleIds == null || !ROLE_IDS_PATTERN.matcher(roleIds).matches()) {
            throw new IllegalArgumentException("非法的角色 ID 列表: " + roleIds);
        }
    }
}
