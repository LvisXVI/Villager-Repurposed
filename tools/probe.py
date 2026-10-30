import socket, struct, sys

def pkt(rid, typ, body):
    data = struct.pack('<ii', rid, typ) + body.encode('utf8') + b'\x00\x00'
    return struct.pack('<i', len(data)) + data

def recv(s):
    raw = b''
    while len(raw) < 4:
        raw += s.recv(4 - len(raw))
    n = struct.unpack('<i', raw)[0]
    buf = b''
    while len(buf) < n:
        buf += s.recv(n - len(buf))
    return buf[8:-2].decode('utf8', 'replace')

s = socket.create_connection(('127.0.0.1', 25575), timeout=60)
s.sendall(pkt(1, 3, 'vptest')); recv(s)
block, y = sys.argv[1], int(sys.argv[2])
x0, x1, z0, z1, step = [int(a) for a in sys.argv[3:8]]
hits = []
for x in range(x0, x1, step):
    for z in range(z0, z1, step):
        s.sendall(pkt(2, 2, f'execute if block {x} {y} {z} {block}'))
        if 'passed' in recv(s):
            hits.append((x, z))
print(len(hits), 'hits')
if hits:
    xs = [h[0] for h in hits]; zs = [h[1] for h in hits]
    print('bbox x', min(xs), max(xs), 'z', min(zs), max(zs))
    # cluster by 12x12 cells
    cells = {}
    for x, z in hits:
        cells.setdefault((x // 12, z // 12), []).append((x, z))
    for k, v in sorted(cells.items()):
        print('cell', k, 'n', len(v), 'x', min(a for a, b in v), max(a for a, b in v), 'z', min(b for a, b in v), max(b for a, b in v))
