package com.quant.platform.system.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.quant.platform.system.dto.DepartmentTreeVO;
import com.quant.platform.system.entity.SysDepartment;
import com.quant.platform.system.mapper.SysDepartmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 部门管理（多级：parent_id 自引用，dept_path 祖先链）
 */
@Service
@RequiredArgsConstructor
public class DepartmentService {

    private final SysDepartmentMapper deptMapper;

    /** 返回部门树（仅启用状态） */
    public List<DepartmentTreeVO> tree() {
        List<SysDepartment> all = deptMapper.selectList(
                new QueryWrapper<SysDepartment>().eq("status", 1)
                        .orderByAsc("sort").orderByAsc("id"));
        Map<Long, DepartmentTreeVO> nodeMap = new LinkedHashMap<>();
        for (SysDepartment d : all) {
            nodeMap.put(d.getId(), new DepartmentTreeVO(
                    d.getId(), d.getParentId(), d.getDeptName(), d.getDeptPath(),
                    d.getDeptLevel(), d.getSort(), d.getStatus()));
        }
        List<DepartmentTreeVO> roots = new ArrayList<>();
        for (SysDepartment d : all) {
            DepartmentTreeVO node = nodeMap.get(d.getId());
            if (d.getParentId() == null || d.getParentId() == 0 || !nodeMap.containsKey(d.getParentId())) {
                roots.add(node);
            } else {
                nodeMap.get(d.getParentId()).getChildren().add(node);
            }
        }
        return roots;
    }

    /** 新增部门：自动计算 dept_path 与 dept_level */
    public SysDepartment create(SysDepartment req) {
        String parentPath = "/";
        int level = 1;
        if (req.getParentId() != null && req.getParentId() != 0) {
            SysDepartment p = deptMapper.selectById(req.getParentId());
            if (p != null) {
                parentPath = p.getDeptPath();
                level = (p.getDeptLevel() == null ? 1 : p.getDeptLevel()) + 1;
            } else {
                req.setParentId(0L);
            }
        } else {
            req.setParentId(0L);
        }
        req.setDeptLevel(level);
        req.setCreateTime(LocalDateTime.now());
        deptMapper.insert(req);
        req.setDeptPath(parentPath + "/" + req.getId());
        deptMapper.updateById(req);
        return req;
    }

    /** 更新部门：父级变化时级联刷新 dept_path / dept_level（含所有子孙） */
    public void update(SysDepartment req) {
        SysDepartment existing = deptMapper.selectById(req.getId());
        if (existing == null) {
            throw new IllegalArgumentException("部门不存在或已删除");
        }
        String parentPath = "/";
        int level = 1;
        if (req.getParentId() != null && req.getParentId() != 0) {
            SysDepartment p = deptMapper.selectById(req.getParentId());
            if (p != null) {
                parentPath = p.getDeptPath();
                level = (p.getDeptLevel() == null ? 1 : p.getDeptLevel()) + 1;
            } else {
                req.setParentId(0L);
            }
        } else {
            req.setParentId(0L);
        }
        String oldPath = existing.getDeptPath();
        String newPath = parentPath + "/" + existing.getId();

        existing.setDeptName(req.getDeptName());
        existing.setParentId(req.getParentId());
        existing.setSort(req.getSort());
        existing.setStatus(req.getStatus());
        existing.setDeptLevel(levelFromPath(newPath));
        existing.setDeptPath(newPath);
        deptMapper.updateById(existing);

        // 级联更新子孙
        if (!oldPath.equals(newPath)) {
            List<SysDepartment> descendants = deptMapper.selectList(
                    new QueryWrapper<SysDepartment>().likeRight("dept_path", oldPath + "/"));
            for (SysDepartment c : descendants) {
                String suffix = c.getDeptPath().substring(oldPath.length());
                c.setDeptPath(newPath + suffix);
                c.setDeptLevel(levelFromPath(c.getDeptPath()));
                deptMapper.updateById(c);
            }
        }
    }

    /** 删除部门：存在子部门时拒绝 */
    public void delete(Long id) {
        Long childCount = deptMapper.selectCount(
                new QueryWrapper<SysDepartment>().eq("parent_id", id));
        if (childCount != null && childCount > 0) {
            throw new IllegalStateException("该部门下存在子部门，无法删除，请先移除子部门");
        }
        deptMapper.deleteById(id);
    }

    /** 由 dept_path 计算层级："/1" → 1；"/1/2" → 2；"/1/2/5" → 3（层级 = 斜杠数 = 路径段数） */
    private int levelFromPath(String path) {
        if (path == null || path.isEmpty()) return 1;
        int cnt = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') cnt++;
        }
        return Math.max(1, cnt);
    }
}
