import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  esbuild: {
    loader: 'jsx',
    include: /src\/.*\.(js|jsx)$/,
    exclude: [],
  },
  optimizeDeps: {
    esbuildOptions: {
      loader: {
        '.js': 'jsx',
        '.jsx': 'jsx',
      },
    },
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // WebSocket 代理：原生 STOMP over WebSocket（注意后端有 context-path: /api）
      '/ws-native': {
        target: 'ws://localhost:8080/api',
        ws: true,
        changeOrigin: true,
      },
      // 兼容旧 SockJS 端点
      '/ws': {
        target: 'ws://localhost:8080/api',
        ws: true,
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'build',
    sourcemap: false,
    rollupOptions: {
      output: {
        // 细粒度 chunk 拆分策略：按页面模块分组，避免单个巨型 chunk
        manualChunks(id) {
          if (id.includes('node_modules')) {
            // React 核心运行时
            if (id.includes('react/') || id.includes('react-dom/') || id.includes('react-router-dom/')) {
              return 'vendor-react';
            }
            // antd 核心 + icons
            if (id.includes('antd/') || id.includes('@ant-design/icons/') || id.includes('rc-')) {
              return 'vendor-antd';
            }
            // ECharts（按需引入，仅包含已注册的图表类型和组件）
            if (id.includes('echarts') || id.includes('zrender')) {
              return 'vendor-echarts';
            }
            // STOMP 库
            if (id.includes('@stomp/stompjs')) {
              return 'vendor-stomp';
            }
            // 工具库
            if (id.includes('lodash') || id.includes('axios') || id.includes('dayjs') || id.includes('numeral')) {
              return 'vendor-utils';
            }
            // 其他 node_modules 打入 vendor-misc
            return 'vendor-misc';
          }
          // 按页面模块分组懒加载 chunk
          if (id.includes('/src/pages/factors/')) return 'page-factors';
          if (id.includes('/src/pages/backtest/')) return 'page-backtest';
          if (id.includes('/src/pages/strategies/')) return 'page-strategies';
          if (id.includes('/src/pages/market/')) return 'page-market';
          if (id.includes('/src/pages/analysis/')) return 'page-analysis';
          if (id.includes('/src/pages/recommendation/')) return 'page-recommendation';
          if (id.includes('/src/pages/dataupdate/') || id.includes('/src/pages/datadetail/')) return 'page-data';
          if (id.includes('/src/pages/manual/')) return 'page-manual';
          if (id.includes('/src/pages/screen/') || id.includes('/src/pages/llm/') || id.includes('/src/pages/monitor/')) return 'page-tools';
          if (id.includes('/src/pages/financial/')) return 'page-financial';
        },
      },
    },
  },
});
