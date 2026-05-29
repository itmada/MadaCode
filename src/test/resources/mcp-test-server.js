#!/usr/bin/env node
// Minimal MCP server for integration testing.
// Provides one tool: echo — returns "Echo: <message>".
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
                capabilities: { tools: {} },
                serverInfo: { name: 'test-server', version: '1.0' }
            }
        });
    } else if (msg.method === 'notifications/initialized') {
        // No response needed for notifications
    } else if (msg.method === 'tools/list') {
        respond({
            jsonrpc: '2.0',
            id: msg.id,
            result: {
                tools: [{
                    name: 'echo',
                    description: 'Echoes back the input message',
                    inputSchema: {
                        type: 'object',
                        properties: {
                            message: { type: 'string', description: 'The message to echo' }
                        },
                        required: ['message']
                    }
                }]
            }
        });
    } else if (msg.method === 'tools/call') {
        const toolName = msg.params && msg.params.name;
        const args = msg.params && msg.params.arguments || {};
        if (toolName === 'echo') {
            respond({
                jsonrpc: '2.0',
                id: msg.id,
                result: {
                    content: [{ type: 'text', text: 'Echo: ' + (args.message || '') }]
                }
            });
        } else {
            respond({
                jsonrpc: '2.0',
                id: msg.id,
                error: { code: -32601, message: 'Unknown tool: ' + toolName }
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
