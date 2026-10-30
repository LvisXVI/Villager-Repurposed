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
def cmd(c):
    s.sendall(pkt(2, 2, c)); return recv(s)
# usage: stock.py x y z item:count ...   (Dorfkiste = Doppelkiste, beide Haelften je 27 Slots)
x, y, z = map(int, sys.argv[1:4])
halves = [(x, y, z)]
for dx, dz in ((1,0),(-1,0),(0,1),(0,-1)):
    if 'chest' in cmd(f'data get block {x+dx} {y} {z+dz} Items')[:0] or True:
        pass
slots = []
for h in [(x, y, z), (x+1, y, z), (x-1, y, z), (x, y, z+1), (x, y, z-1)]:
    out = cmd(f'data get block {h[0]} {h[1]} {h[2]} Items')
    if 'has the following block data' not in out:
        continue
    for i in range(27):
        slots.append((h, i))
    if len(slots) >= 54:
        break
k = 0
for spec in sys.argv[4:]:
    item, count = spec.rsplit(':', 1)
    left = int(count)
    while left > 0 and k < len(slots):
        n = min(64, left)
        (hx, hy, hz), i = slots[k]
        print(cmd(f'item replace block {hx} {hy} {hz} container.{i} with {item} {n}'))
        left -= n; k += 1
