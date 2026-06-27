/**
 * 通用数据导出工具
 * 支持 CSV 和 JSON 格式导出
 */

/**
 * 将表格数据导出为 CSV 文件并触发下载
 *
 * @param {Object} options
 *   - data: Array<Object>  数据行
 *   - columns: Array<{key, title, dataIndex, render?}>  列定义（兼容 antd Table columns 格式）
 *   - filename: string  下载文件名（不含扩展名）
 *   - includeHeader: boolean  是否包含表头（默认 true）
 */
export function exportCsv({ data, columns, filename = 'export', includeHeader = true }) {
  if (!data?.length) return;

  // 提取有效列（排除操作列等无 dataIndex 的列）
  const validCols = columns.filter(c => c.dataIndex || c.key);

  // 构建表头行
  const headerRow = validCols.map(c => {
    const title = c.title || c.dataIndex || c.key || '';
    // CSV 中双引号需要转义
    return `"${String(title).replace(/"/g, '""')}"`;
  });

  // 构建数据行
  const dataRows = data.map(row => {
    return validCols.map(c => {
      const key = c.dataIndex || c.key;
      let value = row[key];

      // 如果列有 render 函数，尝试提取文本（antd render 可能返回 ReactNode）
      if (c.render && value !== undefined && value !== null) {
        // render 函数的第3个参数是完整行数据，但我们只用 value
        try {
          const rendered = c.render(value, row, 0);
          // 如果 render 返回字符串/数字，直接用
          if (typeof rendered === 'string' || typeof rendered === 'number') {
            value = rendered;
          }
          // 如果 render 返回 React 元素（Tag 等），尝试提取文本内容
          else if (rendered?.props?.children) {
            value = rendered.props.children;
          }
        } catch {
          // render 函数执行失败，使用原始值
        }
      }

      // 数值类型保持原样，字符串加引号
      if (value === null || value === undefined) return '';
      if (typeof value === 'number') return value;
      return `"${String(value).replace(/"/g, '""')}"`;
    });
  });

  // 拼装 CSV 内容
  const lines = [];
  if (includeHeader) lines.push(headerRow.join(','));
  dataRows.forEach(row => lines.push(row.join(',')));
  const csvContent = lines.join('\n');

  // 触发下载（UTF-8 + BOM 确保中文在 Excel 中正确显示）
  const blob = new Blob(['\ufeff' + csvContent], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `${filename}.csv`;
  link.click();
  URL.revokeObjectURL(url);
}

/**
 * 将数据导出为 JSON 文件
 *
 * @param {Object} options
 *   - data: Array<Object> | Object  数据
 *   - filename: string  下载文件名
 */
export function exportJson({ data, filename = 'export' }) {
  const jsonContent = JSON.stringify(data, null, 2);
  const blob = new Blob([jsonContent], { type: 'application/json;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `${filename}.json`;
  link.click();
  URL.revokeObjectURL(url);
}
