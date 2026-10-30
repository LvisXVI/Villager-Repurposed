import socket, struct

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

s = socket.create_connection(('127.0.0.1', 25575), timeout=30)
s.sendall(pkt(1, 3, 'vptest')); recv(s)
for x in (-338, -336):
    c = 'summon villager %d 74 -303 {VillagerData:{profession:"minecraft:mason",type:"minecraft:plains",level:2}}' % x
    s.sendall(pkt(2, 2, c)); print(recv(s))
