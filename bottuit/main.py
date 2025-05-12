import asyncio
from aiogram import Bot, Dispatcher, types
from aiogram.types import Message, ReplyKeyboardMarkup, KeyboardButton, InlineKeyboardMarkup, InlineKeyboardButton
from aiogram.filters import Command
from aiogram.client.default import DefaultBotProperties
from aiogram.fsm.state import State, StatesGroup
from aiogram.fsm.context import FSMContext


# Создаём бота и диспетчер
bot = Bot(token="7849095280:AAEwiH6egmnxe_gXCjA5YhGL0EVzKGHoSKs")
dp = Dispatcher()

# Хранилище данных пользователей (логины и пароли)
user_data = {}

# Класс состояний для FSM
class UserState(StatesGroup):
    waiting_for_login = State()
    waiting_for_password = State()

# Главное меню с кнопкой GPA калькулятора
main_keyboard = ReplyKeyboardMarkup(
    keyboard=[
        [KeyboardButton(text="⚙ Управление данными")],
        [KeyboardButton(text="📊 GPA Калькулятор")]
    ],
    resize_keyboard=True
)

# Клавиатура управления данными
data_management_keyboard = ReplyKeyboardMarkup(
    keyboard=[
        [KeyboardButton(text="📋 Мои данные")],
        [KeyboardButton(text="🗑 Удалить данные")],
        [KeyboardButton(text="➕ Добавить данные")],
        [KeyboardButton(text="🔙 Назад")]
    ],
    resize_keyboard=True
)

# Инлайн-клавиатура для GPA калькулятора
gpa_keyboard = InlineKeyboardMarkup(
    inline_keyboard=[
        [InlineKeyboardButton(text="📊 Открыть GPA Калькулятор", url="https://t.me/lmstgbot/gpacalculator")]
    ]
)

@dp.message(Command("start"))
async def start_command(message: Message):
    # Создаём инлайн-клавиатуру
    inline_keyboard = InlineKeyboardMarkup(
        inline_keyboard=[
            [InlineKeyboardButton(text="🌐Открыть LMS", url="https://t.me/lmstgbot/app")]
        ]
    )

    # Отправляем сообщение с инлайн-кнопкой
    await message.answer(
        "Здравствуйте! Я ваш помощник в системе LMS. Моя цель — помочь вам управлять вашими данными для входа, предоставить доступ к системе обучения и упростить взаимодействие с вашим личным кабинетом. Если вы хотите сразу перейти в систему обучения, нажмите на кнопку ниже.",
        reply_markup=inline_keyboard
    )

    await message.answer("Выберите действие ниже.", reply_markup=main_keyboard)

@dp.message(lambda message: message.text == "⚙ Управление данными")
async def manage_data(message: Message):
    await message.answer("Выберите действие:", reply_markup=data_management_keyboard)

@dp.message(lambda message: message.text == "📋 Мои данные")
async def show_data(message: Message):
    user_id = message.from_user.id
    if user_id in user_data and user_data[user_id]:
        buttons = [KeyboardButton(text=d['login']) for d in user_data[user_id]]
        buttons.append(KeyboardButton(text="🔙 Назад"))
        data_keyboard = ReplyKeyboardMarkup(keyboard=[buttons], resize_keyboard=True)
        await message.answer("📋 Ваши сохранённые данные:", reply_markup=data_keyboard)
    else:
        await message.answer("❌ У вас нет сохранённых данных.")

@dp.message(lambda message: message.text == "🗑 Удалить данные")
async def delete_data_menu(message: Message):
    user_id = message.from_user.id
    if user_id not in user_data or not user_data[user_id]:
        await message.answer("❌ У вас нет сохранённых данных для удаления.")
        return

    buttons = [KeyboardButton(text=d['login']) for d in user_data[user_id]]
    buttons.append(KeyboardButton(text="🔙 Назад"))
    delete_keyboard = ReplyKeyboardMarkup(keyboard=[buttons], resize_keyboard=True)
    await message.answer("Выберите логин для удаления:", reply_markup=delete_keyboard)

@dp.message(lambda message: message.text == "➕ Добавить данные")
async def add_data(message: Message, state: FSMContext):
    user_id = message.from_user.id
    if user_id in user_data and len(user_data[user_id]) >= 3:
        await message.answer("❌ Вы можете сохранить только 3 логина!")
        return
    await message.answer("✍ Введите логин:")
    await state.set_state(UserState.waiting_for_login)

@dp.message(UserState.waiting_for_login)
async def get_login(message: Message, state: FSMContext):
    await state.update_data(login=message.text)
    await message.answer("🔒 Теперь введите пароль:")
    await state.set_state(UserState.waiting_for_password)

@dp.message(UserState.waiting_for_password)
async def get_password(message: Message, state: FSMContext):
    user_id = message.from_user.id
    data = await state.get_data()
    login = data['login']
    password = message.text

    if user_id not in user_data:
        user_data[user_id] = []
    user_data[user_id].append({"login": login, "password": password})

    await message.answer(f"✅ Данные сохранены!\n👤 Логин: {login}\n🔑 Пароль: {password}", reply_markup=data_management_keyboard)
    await state.clear()

@dp.message(lambda message: message.text == "🔙 Назад")
async def go_back(message: Message):
    await message.answer("🔙 Возвращаемся в главное меню.", reply_markup=main_keyboard)

@dp.message(lambda message: message.text == "📊 GPA Калькулятор")
async def open_gpa_calculator(message: Message):
    await message.answer("📊 Нажмите кнопку ниже, чтобы открыть GPA Калькулятор:", reply_markup=gpa_keyboard)

# Запуск бота
async def main():
    await bot.delete_webhook(drop_pending_updates=True)
    await dp.start_polling(bot)

if __name__ == '__main__':
    asyncio.run(main())
