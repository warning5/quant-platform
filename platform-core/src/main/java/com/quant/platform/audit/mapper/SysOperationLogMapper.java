package com.quant.platform.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.audit.entity.SysOperationLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysOperationLogMapper extends BaseMapper<SysOperationLog> {
}
