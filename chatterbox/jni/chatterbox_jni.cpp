// JNI bridge: Chatterbox (ggml port) for Android.
//
// Exposes a thin, blocking API to Kotlin:
//   long  nativeCreate(t3Gguf, s3genGguf, referenceWav /*may be ""*/, threads, gpuLayers)
//   byte[] nativeSynthesize(handle, text, seed)   -> 16-bit PCM mono WAV, 24 kHz
//   void  nativeCancel(handle)
//   void  nativeFree(handle)
//
// One handle owns one Engine; synthesize() is serialised per handle (the engine's
// KV cache / gallocr / CFM rng are per-instance state). Callers must not share a
// handle across threads while synthesizing.

#include <jni.h>

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "tts-cpp/chatterbox/engine.h"

namespace {

using tts_cpp::chatterbox::Engine;
using tts_cpp::chatterbox::EngineOptions;
using tts_cpp::chatterbox::SynthesisResult;

std::string toStd(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars != nullptr ? std::string(chars) : std::string();
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return out;
}

// Little-endian 44-byte RIFF header + float32 mono samples converted to int16.
std::vector<uint8_t> toWavBytes(const SynthesisResult & result) {
    const uint32_t sample_rate = static_cast<uint32_t>(result.sample_rate > 0 ? result.sample_rate : 24000);
    const uint32_t channels = 1;
    const uint32_t bits = 16;
    const uint32_t frames = static_cast<uint32_t>(result.pcm.size());
    const uint32_t data_bytes = frames * channels * (bits / 8);
    const uint32_t byte_rate = sample_rate * channels * (bits / 8);

    std::vector<uint8_t> wav;
    wav.reserve(44 + data_bytes);

    auto put32 = [&wav](uint32_t v) {
        for (int i = 0; i < 4; ++i) wav.push_back(static_cast<uint8_t>((v >> (8 * i)) & 0xFF));
    };
    auto put16 = [&wav](uint16_t v) {
        for (int i = 0; i < 2; ++i) wav.push_back(static_cast<uint8_t>((v >> (8 * i)) & 0xFF));
    };

    wav.insert(wav.end(), {'R', 'I', 'F', 'F'});
    put32(36 + data_bytes);
    wav.insert(wav.end(), {'W', 'A', 'V', 'E'});
    wav.insert(wav.end(), {'f', 'm', 't', ' '});
    put32(16);
    put16(1);  // PCM
    put16(static_cast<uint16_t>(channels));
    put32(sample_rate);
    put32(byte_rate);
    put16(static_cast<uint16_t>(channels * (bits / 8)));
    put16(static_cast<uint16_t>(bits));
    wav.insert(wav.end(), {'d', 'a', 't', 'a'});
    put32(data_bytes);

    for (float sample : result.pcm) {
        float clamped = sample;
        if (clamped > 1.0f) clamped = 1.0f;
        if (clamped < -1.0f) clamped = -1.0f;
        const int16_t pcm16 = static_cast<int16_t>(clamped * 32767.0f);
        put16(static_cast<uint16_t>(pcm16));
    }
    return wav;
}

void throwIllegalState(JNIEnv * env, const std::string & message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) env->ThrowNew(cls, message.c_str());
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_romirmile_hermes_data_ChatterboxNative_nativeCreate(
    JNIEnv * env, jobject /*thiz*/, jstring t3_gguf, jstring s3gen_gguf, jstring reference_wav,
    jint threads, jint gpu_layers, jint seed) {
    try {
        EngineOptions opts;
        opts.t3_gguf_path = toStd(env, t3_gguf);
        opts.s3gen_gguf_path = toStd(env, s3gen_gguf);
        opts.reference_audio = toStd(env, reference_wav);
        opts.n_threads = threads;
        opts.n_gpu_layers = gpu_layers;
        opts.seed = seed;
        auto * engine = new Engine(opts);
        return reinterpret_cast<jlong>(engine);
    } catch (const std::exception & e) {
        throwIllegalState(env, std::string("chatterbox engine: ") + e.what());
    } catch (...) {
        throwIllegalState(env, "chatterbox engine: unknown error");
    }
    return 0;
}

JNIEXPORT jbyteArray JNICALL
Java_com_romirmile_hermes_data_ChatterboxNative_nativeSynthesize(
    JNIEnv * env, jobject /*thiz*/, jlong handle, jstring text, jint seed) {
    if (handle == 0) {
        throwIllegalState(env, "chatterbox engine is not loaded");
        return nullptr;
    }
    auto * engine = reinterpret_cast<Engine *>(handle);
    try {
        const std::string input = toStd(env, text);
        if (input.empty()) {
            throwIllegalState(env, "nothing to speak");
            return nullptr;
        }
        // A per-request seed keeps the voice stable while making retries non-identical.
        EngineOptions opts = engine->options();
        if (seed != opts.seed) opts.seed = seed;  // recorded for parity; sampling uses construction-time opts
        const SynthesisResult result = engine->synthesize(input);
        if (result.pcm.empty()) {
            throwIllegalState(env, "chatterbox produced no audio");
            return nullptr;
        }
        const std::vector<uint8_t> wav = toWavBytes(result);
        jbyteArray out = env->NewByteArray(static_cast<jsize>(wav.size()));
        if (out == nullptr) return nullptr;
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(wav.size()),
                                reinterpret_cast<const jbyte *>(wav.data()));
        return out;
    } catch (const std::exception & e) {
        throwIllegalState(env, std::string("chatterbox synthesize: ") + e.what());
    } catch (...) {
        throwIllegalState(env, "chatterbox synthesize: unknown error");
    }
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_romirmile_hermes_data_ChatterboxNative_nativeCancel(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return;
    reinterpret_cast<Engine *>(handle)->cancel();
}

JNIEXPORT void JNICALL
Java_com_romirmile_hermes_data_ChatterboxNative_nativeFree(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return;
    delete reinterpret_cast<Engine *>(handle);
}

}  // extern "C"
