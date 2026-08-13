import { useEffect } from 'react';
import { Canvas } from '@tarojs/components';
import Taro from '@tarojs/taro';

/**
 * 通用折线图（Canvas 2d，微信小程序兼容）。
 * values: number[] —— 纵轴序列
 * opts:
 *   color     折线颜色
 *   dashed    是否虚线
 *   area      是否填充渐变到基线
 *   areaColor 填充色（默认跟随 color 透明）
 *   anchorTop 为 true 时最大值在顶部（回撤图用）
 */
export default function Curve({
  values,
  id = 'curve-' + Math.random().toString(36).slice(2, 8),
  color = '#3B9EFF',
  dashed = false,
  area = false,
  areaColor,
  anchorTop = false,
}) {
  useEffect(() => {
    if (!values || values.length < 2) return;
    let q;
    try { q = Taro.createSelectorQuery(); } catch (e) { return; }
    q.select('#' + id).fields({ node: true, size: true }).exec((res) => {
      if (!res || !res[0] || !res[0].node) return;
      const canvas = res[0].node;
      const ctx = canvas.getContext('2d');
      if (!ctx) return;
      try {
        const dpr = (Taro.getSystemInfoSync && Taro.getSystemInfoSync().pixelRatio) || 2;
        const w = res[0].width, h = res[0].height;
        canvas.width = w * dpr; canvas.height = h * dpr;
        ctx.scale(dpr, dpr);
        ctx.clearRect(0, 0, w, h);

        const min = Math.min.apply(null, values);
        const max = Math.max.apply(null, values);
        const range = (max - min) || 1;
        const pad = 6;

        const pts = values.map((v, i) => {
          const x = pad + (i / (values.length - 1)) * (w - 2 * pad);
          let y;
          if (anchorTop) {
            y = pad + ((v - max) / range) * (h - 2 * pad);
          } else {
            y = h - pad - ((v - min) / range) * (h - 2 * pad);
          }
          return [x, y];
        });

        // 面积填充
        if (area) {
          const fillCol = areaColor || (color + '1A');
          const grad = ctx.createLinearGradient(0, 0, 0, h);
          grad.addColorStop(0, fillCol);
          grad.addColorStop(1, fillCol.replace(/[\da-f]{2}$/i, '00'));
          ctx.beginPath();
          pts.forEach((p, i) => (i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1])));
          ctx.lineTo(pts[pts.length - 1][0], h);
          ctx.lineTo(pts[0][0], h);
          ctx.closePath();
          ctx.fillStyle = grad; ctx.fill();
        }

        // 折线
        ctx.beginPath();
        pts.forEach((p, i) => (i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1])));
        ctx.strokeStyle = color;
        ctx.lineWidth = 2;
        ctx.lineJoin = 'round';
        ctx.lineCap = 'round';
        if (dashed) ctx.setLineDash([4, 4]);
        else ctx.setLineDash([]);
        ctx.stroke();
      } catch (e) { console.warn('[Curve] draw error', e); }
    });
  }, [values, id, color, dashed, area, areaColor, anchorTop]);

  return <Canvas type='2d' id={id} className='curve-canvas' />;
}
