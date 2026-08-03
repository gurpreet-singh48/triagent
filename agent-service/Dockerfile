# Build context is the repo root (see docker-compose.yml) so later phases
# can COPY synthetic-generator/templates/ into this image too.
FROM python:3.14-slim
WORKDIR /app
COPY agent-service/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY agent-service/app ./app
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
