# Messaging & Attachments

Внутренний мессенджер и support-чат поддерживают вложения (фото и файлы).

## Flow отправки сообщения с вложением

### Вариант 1: двухшаговый (upload → send)

**Шаг 1 — загрузка файла:**
```
POST /api/v1/messages/attachments
Content-Type: multipart/form-data

file: <байты файла>
```
Ответ:
```json
{
  "media_id": "uuid",
  "url": "/api/v1/files/media/messages/.../xxx.webp",
  "thumbnail_url": "/api/v1/files/media/messages/.../thumb.webp",
  "filename": "photo.png",
  "content_type": "image/png",
  "size": 1024,
  "kind": "IMAGE"
}
```

**Шаг 2 — отправка сообщения с `attachment_media_id`:**
```
POST /api/v1/messages
Content-Type: application/json

{
  "conversation_id": "uuid",
  "body": "Смотри фото",
  "attachment_media_id": "uuid из шага 1"
}
```

### Вариант 2: one-shot (multipart)

```
POST /api/v1/messages
Content-Type: multipart/form-data

conversation_id: uuid
body: "Смотри фото"
file: <байты файла>
```

## Support-чат с вложением

```
POST /api/v1/support/tickets/{id}/messages
Content-Type: multipart/form-data

body: "Лог ошибки"
file: <байты файла>
```

## Лимиты и валидация

| Параметр | Значение | Конфиг |
|----------|----------|--------|
| Размер файла-вложения | 10 MB (default) | `app.media.max-message-attachment-size` |
| Размер аватара | 5 MB | `app.media.max-avatar-size` |
| Разрешённые MIME | jpg, png, webp, gif, pdf, txt, doc, docx | хардкод в `MediaService` |
| Макс. ширина изображения-вложения | 1920px | `app.media.message-attachment-max-width` |
| Миниатюра | 320×320, WebP | `app.media.message-thumbnail-*` |

Изображения оптимизируются (resize → WebP) и получают миниатюру.
GIF сохраняется в оригинале, миниатюра — статичный WebP.
Документы (PDF/txt/doc/docx) хранятся как есть.

## Обратная совместимость

Старые сообщения без `attachment` работают: `attachment: null` в `MessageResponse`.
`body` остаётся обязательным, если нет вложения; с вложением — опциональным.
