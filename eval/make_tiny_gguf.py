"""生成一个随机权重的微型 Qwen2 结构 GGUF，只用于冒烟测试本机推理库能加载、切词、按语法生成。

词表和对话模板取自 llama.cpp 自带的 models/ggml-vocab-qwen2.gguf；权重随机，输出内容没有意义。
用法：python3 eval/make_tiny_gguf.py <llama.cpp 源码目录> <输出文件>
"""
import sys
from pathlib import Path

import numpy as np


def main():
    llama, out = Path(sys.argv[1]), Path(sys.argv[2])
    sys.path.insert(0, str(llama / "gguf-py"))
    import gguf

    vocab = gguf.GGUFReader(str(llama / "models/ggml-vocab-qwen2.gguf"))

    def field(name):
        f = vocab.fields[name]
        if f.types[0] == gguf.GGUFValueType.ARRAY:
            if f.types[-1] == gguf.GGUFValueType.STRING:
                return [bytes(f.parts[i]).decode("utf-8") for i in f.data]
            return [int(f.parts[i][0]) for i in f.data]
        if f.types[0] == gguf.GGUFValueType.STRING:
            return bytes(f.parts[f.data[0]]).decode("utf-8")
        return f.parts[f.data[0]][0].item()

    tokens = field("tokenizer.ggml.tokens")
    n_vocab, n_embd, n_head, n_head_kv, n_ff, n_layer = len(tokens), 64, 4, 2, 128, 2
    head_dim = n_embd // n_head
    rng = np.random.default_rng(1013)

    def rand(*shape):
        return (rng.standard_normal(shape) * 0.02).astype(np.float32)

    w = gguf.GGUFWriter(str(out), "qwen2")
    w.add_name("offloor-tiny-random")
    w.add_context_length(4096)
    w.add_embedding_length(n_embd)
    w.add_block_count(n_layer)
    w.add_feed_forward_length(n_ff)
    w.add_head_count(n_head)
    w.add_head_count_kv(n_head_kv)
    w.add_rope_freq_base(1000000.0)
    w.add_layer_norm_rms_eps(1e-6)
    w.add_file_type(gguf.LlamaFileType.ALL_F32)
    w.add_tokenizer_model(field("tokenizer.ggml.model"))
    w.add_tokenizer_pre(field("tokenizer.ggml.pre"))
    w.add_token_list(tokens)
    w.add_token_types(field("tokenizer.ggml.token_type"))
    w.add_token_merges(field("tokenizer.ggml.merges"))
    w.add_eos_token_id(field("tokenizer.ggml.eos_token_id"))
    w.add_bos_token_id(field("tokenizer.ggml.bos_token_id"))
    w.add_pad_token_id(field("tokenizer.ggml.padding_token_id"))
    w.add_chat_template(field("tokenizer.chat_template"))

    w.add_tensor("token_embd.weight", rand(n_vocab, n_embd).astype(np.float16))
    w.add_tensor("output_norm.weight", np.ones(n_embd, dtype=np.float32))
    for i in range(n_layer):
        p = f"blk.{i}."
        w.add_tensor(p + "attn_norm.weight", np.ones(n_embd, dtype=np.float32))
        w.add_tensor(p + "attn_q.weight", rand(n_embd, n_embd))
        w.add_tensor(p + "attn_q.bias", rand(n_embd))
        w.add_tensor(p + "attn_k.weight", rand(n_head_kv * head_dim, n_embd))
        w.add_tensor(p + "attn_k.bias", rand(n_head_kv * head_dim))
        w.add_tensor(p + "attn_v.weight", rand(n_head_kv * head_dim, n_embd))
        w.add_tensor(p + "attn_v.bias", rand(n_head_kv * head_dim))
        w.add_tensor(p + "attn_output.weight", rand(n_embd, n_embd))
        w.add_tensor(p + "ffn_norm.weight", np.ones(n_embd, dtype=np.float32))
        w.add_tensor(p + "ffn_gate.weight", rand(n_ff, n_embd))
        w.add_tensor(p + "ffn_up.weight", rand(n_ff, n_embd))
        w.add_tensor(p + "ffn_down.weight", rand(n_embd, n_ff))
    w.write_header_to_file()
    w.write_kv_data_to_file()
    w.write_tensors_to_file()
    w.close()
    print(out)


if __name__ == "__main__":
    main()
