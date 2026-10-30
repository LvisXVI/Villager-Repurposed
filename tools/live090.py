import socket,struct,uuid,re
s=socket.create_connection(('127.0.0.1',25575),timeout=30)
def packet(t,b):
 d=struct.pack('<ii',1,t)+b.encode()+b'\0\0';s.sendall(struct.pack('<i',len(d))+d)
def rec():
 n=struct.unpack('<i',s.recv(4))[0];b=b''
 while len(b)<n:b+=s.recv(n-len(b))
 return b[8:-2].decode('utf-8','replace')
packet(3,'vptest');rec()
def cmd(c):packet(2,c);return rec()
a=cmd('data get block 7333 78 -423 "fabric:attachments"."villagerplus:market"')
for parts in re.findall(r'owner: \[I; ([^\]]+)\]',a):
 v=0
 for number in parts.split(','):v=(v<<32)|(int(number.strip())&0xffffffff)
 ident=str(uuid.UUID(int=v))
 for key in ['Age','Pos','CustomName']:
  print(cmd(f'data get entity {ident} {key}'))
print(cmd('time query daytime'))
