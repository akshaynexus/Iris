#!/usr/bin/env python3
"""Line protocol client. One JSON response per command."""
import argparse
import json
import socket

def command(text, port=47821):
    with socket.create_connection(('127.0.0.1', port), timeout=130) as sock:
        sock.sendall((text + '\n').encode())
        return json.loads(sock.makefile().readline())

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=47821)
    parser.add_argument('command', nargs='+')
    args = parser.parse_args()
    result = command(' '.join(args.command), args.port)
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result.get('ok') else 1)
