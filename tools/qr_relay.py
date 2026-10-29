"""디버그용 중계: 맥의 0.0.0.0:<listen> → 127.0.0.1:<target> (adb forward 포트).
같은 Wi-Fi의 휴대폰이 맥 IP로 에뮬레이터 앱 서버에 닿게 한다.
사용: python3 tools/qr_relay.py 8765 18766   (먼저 adb forward tcp:18766 tcp:8765)"""
import asyncio, sys

LISTEN, TARGET = int(sys.argv[1]), int(sys.argv[2])

async def pipe(reader, writer):
    try:
        while data := await reader.read(65536):
            writer.write(data)
            await writer.drain()
    except ConnectionError:
        pass
    finally:
        writer.close()

async def handle(client_r, client_w):
    peer = client_w.get_extra_info("peername")
    try:
        up_r, up_w = await asyncio.open_connection("127.0.0.1", TARGET)
    except OSError as e:
        print(f"{peer} -> upstream failed: {e}", flush=True)
        client_w.close()
        return
    print(f"{peer} connected", flush=True)
    await asyncio.gather(pipe(client_r, up_w), pipe(up_r, client_w))

async def main():
    server = await asyncio.start_server(handle, "0.0.0.0", LISTEN)
    print(f"relay 0.0.0.0:{LISTEN} -> 127.0.0.1:{TARGET}", flush=True)
    async with server:
        await server.serve_forever()

asyncio.run(main())
