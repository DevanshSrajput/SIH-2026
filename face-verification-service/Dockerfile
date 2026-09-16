# Face verification service.
#
# The models are fetched at BUILD time and baked into the image, so the running
# container never reaches the network. A border post is exactly the deployment that
# cannot assume outbound internet, and a service that starts successfully and then
# cannot load a recognition model is worse than one that fails to build.

FROM python:3.11-slim

WORKDIR /app

# libGL and libglib are what opencv-python-headless still links against; the rest of
# the old X11 list is not needed by the headless build.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libgl1 \
        libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Models first, in their own layer: they are ~40MB and change far less often than the
# code, so editing a source file does not re-download them.
#
# If models/ already holds the files (a local checkout that ran this script once),
# they are copied in and the download is a no-op verification pass.
COPY download_models.py .
COPY models/ models/
RUN python download_models.py

COPY . .

# Not root: this process accepts uploaded images from the public side of a border post.
RUN useradd --system --create-home --uid 10001 facesvc \
    && mkdir -p /data \
    && chown -R facesvc:facesvc /app /data
USER facesvc

# The standalone enrolment gallery. Mounted as a volume in compose so enrolments
# survive a container replacement.
ENV FACE_ENROLMENT_DB=/data/enrolments.json

EXPOSE 5000

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:5000/health').status == 200 else 1)"

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "5000"]
