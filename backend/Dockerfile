FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PORT=7860

WORKDIR /app

# Create non-root user with UID 1000 (compatible with Hugging Face Spaces security policy)
RUN useradd --create-home --uid 1000 --shell /bin/bash appuser

COPY requirements.txt ./
RUN pip install --no-cache-dir --upgrade pip \
    && pip install --no-cache-dir -r requirements.txt

COPY --chown=appuser:appuser app ./app

USER appuser

EXPOSE 7860

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD python -c "import os, urllib.request; p = os.environ.get('PORT', '7860'); urllib.request.urlopen('http://127.0.0.1:' + p + '/api/v1/health', timeout=3)" || exit 1

CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-7860}"]
