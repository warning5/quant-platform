import { useState, useEffect, useRef, useCallback } from 'react';
import { Client } from '@stomp/stompjs';

/**
 * 统一 STOMP WebSocket Hook
 * 基于 @stomp/stompjs Client，统一连接管理、自动重连、心跳
 * 替代各页面手动构造 WebSocket + STOMP 帧的做法
 *
 * @param {Object} options
 *   - brokerURL: WebSocket 连接地址（默认自动拼接 ws://host/ws-native）
 *   - subscriptions: { [dest]: (data) => void }  订阅主题及回调
 *   - onConnect: () => void  连接成功回调
 *   - onDisconnect: () => void  断开回调
 *   - reconnectDelay: number  重连延迟(ms)，默认5000
 *   - enabled: boolean  是否启用连接（默认true），可传 false 暂停连接
 *
 * @returns {{ connected: boolean, send: (dest, body) => void, clientRef: React.MutableRefObject }}
 */
export function useStompWebSocket({
  brokerURL,
  subscriptions = {},
  onConnect,
  onDisconnect,
  reconnectDelay = 5000,
  enabled = true,
}) {
  const [connected, setConnected] = useState(false);
  const clientRef = useRef(null);
  const subIdsRef = useRef([]);

  // 动态拼接默认 WS URL
  const wsUrl = brokerURL || (
    (window.location.protocol === 'https:' ? 'wss:' : 'ws:') +
    '//' + window.location.host + '/ws-native'
  );

  // 发送消息到指定 destination
  const send = useCallback((destination, body = '') => {
    if (clientRef.current?.active) {
      clientRef.current.publish({ destination, body });
    }
  }, []);

  useEffect(() => {
    if (!enabled) {
      // 不启用时，断开已有连接
      if (clientRef.current?.active) {
        clientRef.current.deactivate();
      }
      setConnected(false);
      return;
    }

    const client = new Client({
      brokerURL: wsUrl,
      reconnectDelay,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        setConnected(true);
        // 执行所有订阅
        subIdsRef.current = [];
        for (const [dest, handler] of Object.entries(subscriptions)) {
          const subscription = client.subscribe(dest, (frame) => {
            try {
              const data = JSON.parse(frame.body);
              handler(data);
            } catch {
              // 非 JSON 消息，直接传递原始内容
              handler(frame.body);
            }
          });
          subIdsRef.current.push(subscription);
        }
        onConnect?.();
      },
      onDisconnect: () => {
        setConnected(false);
        onDisconnect?.();
      },
      onWebSocketClose: () => {
        setConnected(false);
      },
    });

    clientRef.current = client;
    client.activate();

    return () => {
      // 清理：取消订阅 + 断开连接
      subIdsRef.current.forEach(sub => {
        try { sub.unsubscribe(); } catch {}
      });
      subIdsRef.current = [];
      if (client.active) {
        client.deactivate();
      }
      clientRef.current = null;
    };
  }, [enabled, wsUrl, reconnectDelay]); // 注意：subscriptions 不作为依赖，避免频繁重连

  return { connected, send, clientRef };
}
