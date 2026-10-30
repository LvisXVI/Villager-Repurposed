import socket, struct, sys, re
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
bell = sys.argv[1:4]; chest = sys.argv[4:7]
out = cmd(f'data get block {bell[0]} {bell[1]} {bell[2]} fabric:attachments."villagerplus:village".wanted')
ids = re.findall(r'"([a-z_0-9:]+)#(\d+)"', out)
items = []
for i, n in ids:
    left = int(n)
    while left > 0:
        items.append((i, min(64, left))); left -= 64
base = ['minecraft:emerald 40', 'minecraft:oak_planks 64', 'minecraft:cobblestone 64', 'minecraft:cobblestone 64', 'minecraft:wheat_seeds 64',
        'minecraft:torch 32', 'minecraft:iron_ingot 32', 'minecraft:coal 32', 'minecraft:bread 32', 'minecraft:oak_log 64']
halves = [(int(chest[0]), int(chest[1]), int(chest[2]))]
for dx, dz in ((1,0),(-1,0),(0,1),(0,-1)):
    x, y, z = int(chest[0])+dx, int(chest[1]), int(chest[2])+dz
    if 'passed' in cmd(f'execute if block {x} {y} {z} minecraft:chest'):
        halves.append((x, y, z)); break
def put(slot, spec):
    h = halves[min(slot // 27, len(halves)-1)]
    cmd(f'item replace block {h[0]} {h[1]} {h[2]} container.{slot % 27} with {spec}')
slot = 0
for spec in base:
    put(slot, spec); slot += 1
for i, n in items:
    if slot >= 52: break
    put(slot, f'{i} {n}'); slot += 1
for b in ('minecraft:white_bed', 'minecraft:white_bed', 'minecraft:oak_door'):
    if slot < 54:
        put(slot, b); slot += 1
print('gefuellt:', slot, 'Slots; Wunschliste:', len(items))
