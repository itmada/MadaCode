#!/usr/bin/env node
'use strict';

const readline = require('readline');

const rl = readline.createInterface({ input: process.stdin, terminal: false });

rl.on('line', (line) => {
    if (!line.trim()) return;
    let msg;
    try {
        msg = JSON.parse(line);
    } catch (_) {
        return;
    }

    if (msg.method === 'initialize') {
        respond({
            jsonrpc: '2.0',
            id: msg.id,
            result: {
                protocolVersion: '2024-11-05',
                capabilities: { resources: {}, tools: {} },
                serverInfo: { name: 'resources-test-server', version: '1.0' }
            }
        });
    } else if (msg.method === 'notifications/initialized') {
        // no response
    } else if (msg.method === 'tools/list') {
        respond({
            jsonrpc: '2.0',
            id: msg.id,
            result: { tools: [] }
        });
    } else if (msg.method === 'resources/list') {
        respond({
            jsonrpc: '2.0',
            id: msg.id,
            result: {
                resources: [
                    { uri: 'test://text/hello', name: 'hello', mimeType: 'text/plain', description: 'A friendly greeting' },
                    { uri: 'test://text/markdown', name: 'doc', mimeType: 'text/markdown' },
                    { uri: 'test://binary/png', name: 'icon', mimeType: 'image/png' }
                ]
            }
        });
    } else if (msg.method === 'resources/read') {
        const uri = msg.params && msg.params.uri;
        if (uri === 'test://text/hello') {
            respond({
                jsonrpc: '2.0',
                id: msg.id,
                result: {
                    contents: [{ uri, mimeType: 'text/plain', text: 'hello world' }]
                }
            });
        } else if (uri === 'test://text/markdown') {
            respond({
                jsonrpc: '2.0',
                id: msg.id,
                result: {
                    contents: [{ uri, mimeType: 'text/markdown', text: '# Hello\nThis is markdown.' }]
                }
            });
        } else if (uri === 'test://binary/png') {
            const blob = Buffer.from('PNG_FAKE_BYTES_123').toString('base64');
            respond({
                jsonrpc: '2.0',
                id: msg.id,
                result: {
                    contents: [{ uri, mimeType: 'image/png', blob }]
                }
            });
        } else {
            respond({
                jsonrpc: '2.0',
                id: msg.id,
                error: { code: -32602, message: 'Resource not found: ' + uri }
            });
        }
    } else if (msg.method === 'shutdown') {
        if (msg.id !== undefined) {
            respond({ jsonrpc: '2.0', id: msg.id, result: null });
        }
        process.exit(0);
    } else if (msg.id !== undefined) {
        respond({
            jsonrpc: '2.0',
            id: msg.id,
            error: { code: -32601, message: 'Method not found: ' + msg.method }
        });
    }
});

function respond(obj) {
    process.stdout.write(JSON.stringify(obj) + '\n');
}
