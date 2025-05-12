import aiofiles
from aiogram import Bot, Dispatcher
from aiogram.types import Message
from aiogram.filters import Command
from aiogram import F
import yt_dlp as youtube_dl
import asyncio
import os
from aiogram.types import FSInputFile

from TOKEN import TOKEN

bot = Bot(TOKEN)
dp = Dispatcher()

async def start_handler(message: Message):
    await message.answer("Привет! Я скачиваю видео с соц. сетей. \nЖиво отправь ссылку")

async def text_message_handler(message: Message):
    url = message.text
    if url.startswith(('https://youtu.be/', 'https://www.youtube.com/', 'https://youtube.com/')):
        try:
            with youtube_dl.YoutubeDL() as ydl:
                info = ydl.extract_info(url, download=False)
                title = info.get('title', 'Видео')
                uploader = info.get('uploader', 'Неизвестный автор')
                await message.answer(
                    f"Начинаю загрузку: {title}\n"
                    f"С канала *[{uploader}]({info.get('channel_url', '')})*",
                    parse_mode="Markdown"
                )
                await download_youtube(url, message)
        except Exception as e:
            await message.answer(f"Произошла ошибка: {str(e)}")
            print(f"Ошибка: {str(e)}")

async def download_youtube(url, message):
    file_path = None
    try:
        ydl_opts = {
            'format': 'bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best',
            'outtmpl': f"{message.chat.id}_%(title)s.%(ext)s",
            'merge_output_format': 'mp4',
            'postprocessors': [{
                'key': 'FFmpegVideoConvertor',
                'preferedformat': 'mp4',
            }],
        }

        print("Скачивание...")
        with youtube_dl.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=True)
            file_path = ydl.prepare_filename(info)
            if not file_path.endswith('.mp4'):
                file_path = file_path.rsplit('.', 1)[0] + '.mp4'

        if os.path.getsize(file_path) > 50 * 1024 * 1024:
            await message.answer("Размер видео превышает 50MB, я не могу его отправить.")
            return

        print(f"Отправка {file_path}...")
        if file_path and os.path.exists(file_path):
            video = FSInputFile(path=file_path)
            await bot.send_video(chat_id=message.chat.id, video=video, caption="Вот твое видео")
            print("Видео успешно отправлено.")
    except Exception as e:
        await message.answer(f"Не удалось отправить видео: {str(e)}")
        print(f"Ошибка при отправке видео: {str(e)}")
    finally:
        if file_path and os.path.exists(file_path):
            try:
                os.remove(file_path)
                print(f"{file_path} удален.")
            except Exception as e:
                print(f"Ошибка при удалении файла: {str(e)}")

async def main():
    dp.message.register(start_handler, Command(commands=["start"]))
    dp.message.register(text_message_handler, F.text)
    await dp.start_polling(bot)

if __name__ == '__main__':
    asyncio.run(main())