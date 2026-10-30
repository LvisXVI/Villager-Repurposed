import socket, struct, sys, time, re
# Testlauf: legt nacheinander Bauauftraege an (vpplan, nur Entwicklungsumgebung) und wartet, bis die Maurer sie fertig haben.
# usage: plans.py <Spielername> <bell x y z> typ:seed:x:z ...
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
s = socket.create_connection(('127.0.0.1', 25575), timeout=120)
s.sendall(pkt(1, 3, 'vptest')); recv(s)
def cmd(c):
    s.sendall(pkt(2, 2, c)); return recv(s)
player = sys.argv[1]
bx, by, bz = sys.argv[2:5]
base = f'data get block {bx} {by} {bz} fabric:attachments."villagerplus:village".'
for spec in sys.argv[5:]:
    typ, seed, x, z = spec.split(':')
    # warten, bis kein anderer Auftrag laeuft (Dorf-eigene Plaene haben Vorrang)
    t0 = time.time()
    while time.time() - t0 < 900 and 'Found no elements' not in cmd(base + 'plan_type'):
        time.sleep(10)
    cmd(f'tp {player} {x} 150 {z}')
    time.sleep(4)
    cmd(f'execute as {player} at {player} positioned over world_surface run tp {player} ~ ~ ~ 0 0')
    out = cmd(f'execute as {player} at {player} run vpplan {typ} {seed}')
    origin = cmd(base + 'plan_origin')
    print(spec, '->', out, origin.split('data: ')[-1], flush=True)
    if 'angelegt' not in out:
        continue
    t0 = time.time()
    done = False
    while time.time() - t0 < 900:
        time.sleep(10)
        if 'Found no elements' in cmd(base + 'plan_type'):
            done = True
            break
    print(spec, 'fertig' if done else 'TIMEOUT', int(time.time() - t0), 's', flush=True)
