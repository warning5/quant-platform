package com.quant.platform.mp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.quant.platform.mp.domain.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MpUserMapper extends BaseMapper<SysUser> {

    @Select("SELECT * FROM sys_user WHERE wechat_unionid = #{unionid} AND deleted = 0 LIMIT 1")
    SysUser selectByUnionid(String unionid);

    @Select("SELECT * FROM sys_user WHERE wechat_openid = #{openid} AND deleted = 0 LIMIT 1")
    SysUser selectByOpenid(String openid);
}
