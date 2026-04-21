const WebSocket = require('ws');

// Connect via Vite proxy (same as browser)
const ws = new WebSocket('ws://localhost:3000/ws-native');

ws.on('open', () => {
  console.log('CONNECTED to Vite proxy');
  const frame = 'CONNECT\naccept-version:1.2\nheart-beat:10000,10000\n\n\x00';
  ws.send(frame);
});

ws.on('message', (data) => {
  const str = data.toString();
  console.log('MSG:', str.substring(0, 300));
  
  if (str.includes('CONNECTED')) {
    console.log('STOMP CONNECTED, subscribing...');
    const sub = 'SUBSCRIBE\nid:test-sub\ndestination:/topic/factor/batch-log\n\n\x00';
    ws.send(sub);
    
    setTimeout(() => {
      console.log('Triggering test via backend REST...');
      const http = require('http');
      http.get('http://localhost:8080/api/factors/ws-test', (res) => {
        let body = '';
        res.on('data', c => body += c);
        res.on('end', () => console.log('Trigger response:', body));
      });
    }, 1000);
  }
});

ws.on('error', (err) => console.error('WS ERROR:', err.message));
ws.on('close', (code, reason) => console.log('WS CLOSED:', code, reason.toString()));

setTimeout(() => { ws.close(); process.exit(0); }, 8000);
