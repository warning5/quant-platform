import { useCallback, useEffect, useRef } from 'react';

/**
 * 通用 ResizeObserver Hook
 * 监听目标元素尺寸变化，回调中返回 { width, height }
 *
 * 用法：
 *   const containerRef = useRef(null);
 *   const size = useResizeObserver(containerRef);
 *   // size = { width: 800, height: 600 } 或 null（首次未触发）
 *
 * 也可传入回调：
 *   useResizeObserver(containerRef, (entry) => { ... });
 */
export function useResizeObserver(targetRef, onResize) {
  const sizeRef = useRef(null);
  const cbRef = useRef(onResize);
  cbRef.current = onResize;

  useEffect(() => {
    const el = targetRef.current;
    if (!el) return;

    const observer = new ResizeObserver((entries) => {
      for (const entry of entries) {
        const { width, height } = entry.contentRect;
        sizeRef.current = { width, height };
        if (cbRef.current) {
          cbRef.current(entry);
        }
      }
    });

    observer.observe(el);
    return () => observer.disconnect();
  }, [targetRef]);

  const getSize = useCallback(() => sizeRef.current, []);

  return getSize;
}
