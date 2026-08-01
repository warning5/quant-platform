package com.quant.platform.dataperm.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import com.quant.platform.common.annotation.ResourceMeta;
import com.quant.platform.dataperm.mapper.ResourceMetaMapper;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;

/**
 * 自动写入资源归属（方案C 命门：双写一致性）。
 *
 * 为什么不用 MyBatis-Plus InnerInterceptor：3.5.10.1 的 InnerInterceptor 只有 beforeUpdate（在 SQL 执行前触发，
 * 自增主键尚未回填），没有 afterInsert/afterUpdate，无法在执行后拿到刚生成的 id。
 * 因此改用 Spring AOP 拦截 4 个受控 Mapper 的 insert——insert 返回后实体 id 已回填，此时写 resource_meta 最可靠。
 *
 * 业务代码零改动。即便 AOP 因极端情况未触发，每日孤儿对账任务（OrphanReconcileTask）也会兜底补 meta。
 */
@Aspect
@Component
public class ResourceMetaAspect implements ApplicationContextAware {

    private ApplicationContext applicationContext;
    private ResourceMetaMapper resourceMetaMapper;

    private ResourceMetaMapper mapper() {
        if (resourceMetaMapper == null) {
            resourceMetaMapper = applicationContext.getBean(ResourceMetaMapper.class);
        }
        return resourceMetaMapper;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @AfterReturning(
            pointcut = "execution(* com.quant.platform.strategy.mapper.StrategyDefinitionMapper.insert(..)) || " +
                    "execution(* com.quant.platform.factor.mapper.FactorDefinitionMapper.insert(..)) || " +
                    "execution(* com.quant.platform.backtest.mapper.BacktestTaskMapper.insert(..)) || " +
                    "execution(* com.quant.platform.strategy.paper.PaperTradingMapper.insert(..))",
            returning = "ret")
    public void afterResourceInsert(JoinPoint jp, Object ret) {
        Object[] args = jp.getArgs();
        if (args == null || args.length == 0) {
            return;
        }
        Object entity = args[0];
        if (entity == null) {
            return;
        }
        ResourceMeta anno = AnnotationUtils.findAnnotation(entity.getClass(), ResourceMeta.class);
        if (anno == null) {
            return;
        }
        if (!StpUtil.isLogin()) {
            return;
        }
        Method getId = ReflectionUtils.findMethod(entity.getClass(), "getId");
        if (getId == null) {
            return;
        }
        Object idVal = ReflectionUtils.invokeMethod(getId, entity);
        if (!(idVal instanceof Long)) {
            return;
        }
        Long rid = (Long) idVal;
        Long uid = StpUtil.getLoginIdAsLong();
        Long deptId = currentDeptId();
        mapper().insertIgnore(anno.value().getCode(), rid, uid, deptId);
    }

    private Long currentDeptId() {
        Object d = StpUtil.getSession().get("deptId");
        if (d instanceof Long) {
            return (Long) d;
        }
        if (d instanceof Number) {
            return ((Number) d).longValue();
        }
        return 0L;
    }
}
