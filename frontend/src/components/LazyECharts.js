/**
 * 按需引入的 ECharts React 包装组件
 * 使用 echarts-for-react/lib/core（不内置完整 echarts）+ echartsSetup（只注册项目用到的图表类型）
 * 替代所有页面的 `import ReactECharts from 'echarts-for-react'`
 *
 * 内置响应式高度：
 *   - 小屏(<768px) 自动将固定像素高度缩减为 mobileHeight（默认原高度的60%）
 *   - autoResize 默认开启（echarts-for-react size-sensor），宽度变化自动触发 .resize()
 *
 * 使用方式：import ReactECharts from '../../components/LazyECharts';
 * 其余用法与原 ReactECharts 完全一致
 *
 * 新增 props:
 *   - mobileHeight: number | 'auto'  小屏下图表高度，默认取 style.height * 0.6
 */
import React, { useMemo } from 'react';
import ReactEChartsCore from 'echarts-for-react/lib/core';
import echarts from '../echartsSetup';

// 响应式高度：小屏下将固定像素高度缩减
function useResponsiveHeight(style, mobileHeight) {
  const currentHeight = style?.height ?? 400;
  const isMobile = typeof window !== 'undefined' && window.innerWidth < 768;

  return useMemo(() => {
    if (!isMobile) return currentHeight;

    if (mobileHeight === 'auto') return Math.max(200, Math.round(window.innerWidth * 0.55));
    if (typeof mobileHeight === 'number') return mobileHeight;
    // 默认：原高度 * 0.6，最小 200px
    if (typeof currentHeight === 'number') return Math.max(200, Math.round(currentHeight * 0.6));
    // 字符串高度（如 '100%'）不变
    return currentHeight;
  }, [currentHeight, mobileHeight, isMobile]);
}

// 用 forwardRef 暴露底层 EChartsReactCore 实例，便于调用方通过 ref.current.getEchartsInstance()
// 直接操作图表（如 zr 事件绑定、convertFromPixel 像素反查等）
const LazyECharts = React.forwardRef(function LazyECharts({ style, mobileHeight, ...rest }, ref) {
  const responsiveHeight = useResponsiveHeight(style, mobileHeight);

  const mergedStyle = useMemo(() => ({
    ...style,
    height: responsiveHeight,
  }), [style, responsiveHeight]);

  return <ReactEChartsCore ref={ref} echarts={echarts} style={mergedStyle} {...rest} />;
});

export default LazyECharts;
