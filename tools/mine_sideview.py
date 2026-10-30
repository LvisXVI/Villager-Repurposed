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
z = -338
print('x from -326 to -300; rows y=78 down to 44 (A=air, #=solid, ~=other)')
for y in range(78, 43, -1):
    row = ''
    for x in range(-326, -299):
        ch = '#'
        for blk, c in (('minecraft:air','A'),('minecraft:cave_air','A'),('minecraft:water','~'),('minecraft:lava','L')):
            s.sendall(pkt(2, 2, f'execute if block {x} {y} {z} {blk}'))
            if 'passed' in recv(s):
                ch = c; break
        row += ch
    print(y, row)
