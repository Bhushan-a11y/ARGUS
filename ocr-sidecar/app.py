from fastapi import FastAPI, UploadFile, File
from rapidocr_onnxruntime import RapidOCR
import uvicorn

app = FastAPI()
ocr = RapidOCR()

@app.post("/ocr")
async def perform_ocr(image_file: UploadFile = File(...)):
    contents = await image_file.read()
    result, _ = ocr(contents)
    if not result:
        return {"code": 0, "data": []}
    return {
        "code": 0,
        "data": [{"text": str(line[1]), "score": float(line[2])} for line in result]
    }

@app.get("/health")
def health_check():
    return {"status": "ok"}

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=9090)
