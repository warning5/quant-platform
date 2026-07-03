/**
 * ECharts 按需引入统一入口
 * 只注册项目实际使用的图表类型和组件，避免引入完整包（~1MB）
 * 使用此模块代替 `import echarts from 'echarts'`
 *
 * 使用方式：
 *   import ReactECharts from 'echarts-for-react';
 *   import '../../echartsSetup';  // 或在页面顶部引入一次即可
 *   // 之后正常使用 ReactECharts 组件
 */

import * as echarts from 'echarts/core';

// ── 图表类型 ──
import { BarChart, LineChart, PieChart, ScatterChart, HeatmapChart, RadarChart, GaugeChart, CandlestickChart } from 'echarts/charts';

// ── 组件 ──
import {
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
  DatasetComponent,
  TransformComponent,
  ToolboxComponent,
  DataZoomComponent,
  VisualMapComponent,
  MarkLineComponent,
  MarkPointComponent,
  GraphicComponent,
} from 'echarts/components';

// ── 渲染器 ──
import { CanvasRenderer } from 'echarts/renderers';

// 注册所有需要的内容
echarts.use([
  BarChart, LineChart, PieChart, ScatterChart, HeatmapChart, RadarChart, GaugeChart, CandlestickChart,
  TitleComponent, TooltipComponent, LegendComponent, GridComponent,
  DatasetComponent, TransformComponent, ToolboxComponent, DataZoomComponent,
  VisualMapComponent, MarkLineComponent, MarkPointComponent, GraphicComponent,
  CanvasRenderer,
]);

export default echarts;
