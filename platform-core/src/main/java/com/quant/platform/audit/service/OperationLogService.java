package com.quant.platform.audit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quant.platform.audit.entity.SysOperationLog;
import com.quant.platform.audit.mapper.SysOperationLogMapper;
import com.quant.platform.common.dto.PageRequest;
import com.quant.platform.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class OperationLogService {

    private final SysOperationLogMapper logMapper;

    private static final DateTimeFormatter F1 = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public IPage<SysOperationLog> page(PageRequest req,
                                       String username, String module, String action,
                                       String startTime, String endTime) {
        Page<SysOperationLog> page = new Page<>(req.getPage() + 1, req.getSize());
        LambdaQueryWrapper<SysOperationLog> q = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(username)) {
            q.like(SysOperationLog::getUsername, username);
        }
        if (StringUtils.hasText(module)) {
            q.like(SysOperationLog::getModule, module);
        }
        if (StringUtils.hasText(action)) {
            q.eq(SysOperationLog::getAction, action);
        }
        if (StringUtils.hasText(startTime)) {
            q.ge(SysOperationLog::getOperationTime, parseTime(startTime));
        }
        if (StringUtils.hasText(endTime)) {
            q.le(SysOperationLog::getOperationTime, parseTime(endTime));
        }
        q.eq(SysOperationLog::getDeleted, 0).orderByDesc(SysOperationLog::getOperationTime);
        return logMapper.selectPage(page, q);
    }

    public void delete(Long id) {
        SysOperationLog log = logMapper.selectById(id);
        if (log == null) {
            throw new BusinessException("日志不存在");
        }
        log.setDeleted(1);
        logMapper.updateById(log);
    }

    private LocalDateTime parseTime(String s) {
        if (s.contains(":")) {
            return LocalDateTime.parse(s.trim(), F1);
        }
        return LocalDateTime.parse(s.trim() + " 00:00:00", F1);
    }
}
