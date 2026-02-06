####
# Multi-stage Docker build for Python clients
#
# Build the image with:
# docker build -f clients/python/Dockerfile -t paper-rock-scissors-python-client .
#
# Run the unary client:
# docker run --rm --network host paper-rock-scissors-python-client python3 unary_client.py
#
# Run the streaming client:
# docker run --rm --network host paper-rock-scissors-python-client python3 streaming_client.py
###
FROM python:3.12-slim

WORKDIR /app

# Copy requirements and install dependencies
COPY clients/python/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy proto files
COPY src/main/proto/ /app/proto/

# Copy client scripts
COPY clients/python/*.py .
COPY clients/python/generate_protos.sh .

# Generate gRPC stubs
RUN chmod +x generate_protos.sh && \
    sed -i 's|../../src/main/proto|/app/proto|g' generate_protos.sh && \
    ./generate_protos.sh

# Make clients executable
RUN chmod +x unary_client.py streaming_client.py

CMD ["python3", "--version"]
