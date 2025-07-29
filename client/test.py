a = b"\x00\x00\x00\x01"
for i in range(4):
    print(type(a[i:i+1]))  # This will print each byte of the byte string
