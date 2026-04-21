const WebSocket = require('ws');

const ws = new WebSocket('ws://localhost:3000/ws-native');

let gotMessage = false;

ws.on('open', () => {
  console.log('1. WS OPEN');
  const frame = 'CONNECT\naccept-version:1.2\nheart-beat:10000,10000\n\n\x00';
  ws.send(frame);
});

ws.on('message', (data) => {
  const str = data.toString();
  console.log('2. MSG received:', str.substring(0, 200));
  
  if (str.includes('CONNECTED')) {
    console.log('3. STOMP CONNECTED');
    const sub = 'SUBSCRIBE\nid:test-sub\ndestination:/topic/factor/batch-log\n\n\x00';
    ws.send(sub);
    console.log('4. SUBSCRIBE sent');
    
    setTimeout(() => {
      console.log('5. Triggering ws-test endpoint...');
      const http = require('http');
      http.get('http://localhost:8080/api/factors/ws-test', (res) => {
        let body = '';
        res.on('data', c => body += c);
        res.on('end', () => {
          console.log('6. Trigger response:', body);
          // Wait 2 more seconds for the message
          setTimeout(() => {
            console.log('7. Final check - gotMessage:', gotMessage);
            ws.close();
            process.exit(0);
          }, 2000);
        });
      });
    }, 1000);
  } else if (str.includes('MESSAGE')) {
    gotMessage = true;
    console.log('6. GOT MESSAGE:', str.substring(0, 300));
  }
});

ws.on('error', (err) => console.error('ERROR:', err.message));
ws.on('close', (code, reason) => console.log('CLOSED:', code));
