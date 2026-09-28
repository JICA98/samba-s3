import re

with open("app/src/main/cpp/rpcsx/rpcs3/Emu/Cell/SPUCommonRecompiler.cpp", "r") as f:
    content = f.read()

replacement = """		return fn;
#elif defined(ARCH_ARM64)
		const auto add_loc = m_spurt->add_empty(std::move(_func));

		if (!add_loc)
		{
			return nullptr;
		}

		if (add_loc->compiled)
		{
			return add_loc->compiled;
		}

		const spu_program& func = add_loc->data;

		if (g_cfg.core.spu_debug && !add_loc->logged.exchange(1))
		{
			std::string log;
			this->dump(func, log);
			fs::write_file(m_spurt->get_cache_path() + "spu.log", fs::create + fs::write + fs::append, log);
		}

		// Allocate executable area for the gate stub
		const auto result = jit_runtime::alloc(64, 16);

		if (!result)
		{
			return nullptr;
		}

		{
			sha1_context ctx;
			u8 output[20];

			sha1_starts(&ctx);
			sha1_update(&ctx, reinterpret_cast<const u8*>(func.data.data()), func.data.size() * 4);
			sha1_finish(&ctx, output);

			be_t<u64> hash_start;
			std::memcpy(&hash_start, output, sizeof(hash_start));
			m_hash_start = hash_start;
		}

#if defined(__APPLE__)
		pthread_jit_write_protect_np(false);
#endif

		u32* raw32 = reinterpret_cast<u32*>(result);
		*raw32++ = 0x1000000f; // adr x15, gate
		*raw32++ = 0x58000170; // ldr x16, ptr_compiled
		*raw32++ = 0xc8dffe11; // ldar x17, [x16]
		*raw32++ = 0xeb0f023f; // cmp x17, x15
		*raw32++ = 0x54000080; // b.eq fallback
		*raw32++ = 0xb4000071; // cbz x17, fallback
		*raw32++ = 0xd5033fdf; // isb
		*raw32++ = 0xd61f0220; // br x17
		// fallback:
		*raw32++ = 0x580000d0; // ldr x16, ptr_dispatch
		*raw32++ = 0xf9400211; // ldr x17, [x16]
		*raw32++ = 0xd61f0220; // br x17
		*raw32++ = 0xd503201f; // nop (alignment)

		u64* raw64 = reinterpret_cast<u64*>(raw32);
		*raw64++ = reinterpret_cast<u64>(&add_loc->compiled);
		*raw64++ = reinterpret_cast<u64>(&spu_runtime::tr_dispatch);

		__builtin___clear_cache(reinterpret_cast<char*>(result), reinterpret_cast<char*>(raw64));

#if defined(__APPLE__)
		pthread_jit_write_protect_np(true);
#endif

		const auto fn = reinterpret_cast<spu_function_t>(result);

		// Install pointer carefully
		const bool added = !add_loc->compiled && add_loc->compiled.compare_and_swap_test(nullptr, fn);

		const bool inverse_bounds = g_cfg.core.spu_llvm_lower_bound > g_cfg.core.spu_llvm_upper_bound;

		if ((!inverse_bounds && (m_hash_start < g_cfg.core.spu_llvm_lower_bound || m_hash_start > g_cfg.core.spu_llvm_upper_bound)) ||
			(inverse_bounds && (m_hash_start < g_cfg.core.spu_llvm_lower_bound && m_hash_start > g_cfg.core.spu_llvm_upper_bound)))
		{
			spu_log.error("[Debug] Skipped function %s", fmt::base57(be_t<u64>{m_hash_start}));
		}
		else if (added)
		{
			// Send work to LLVM compiler thread
			g_fxo->get<spu_llvm_thread>().registered.push(m_hash_start, add_loc);
		}

		if (!m_spurt->rebuild_ubertrampoline(func.data[0]))
		{
			return nullptr;
		}

		if (added)
		{
			add_loc->compiled.notify_all();
		}

		return fn;
#endif
	}"""

target = """		return fn;
#endif
	}"""

if target in content:
    content = content.replace(target, replacement)
    with open("app/src/main/cpp/rpcsx/rpcs3/Emu/Cell/SPUCommonRecompiler.cpp", "w") as f:
        f.write(content)
    print("Patched successfully.")
else:
    print("Target not found.")
