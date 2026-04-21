const http = require('http');
const WebSocket = require('/ws'); // will fail, use global

// Test using plain HTTP upgrade
const ws = new (require('ws'))('ws://localhost:8080/api/ws-native');
