#include "rpcs3/Emu/CPU/Backends/AArch64/AArch64FeaturePolicy.h"

#include <algorithm>
#include <cassert>

namespace
{
	using namespace aarch64::feature_policy;

	bool contains(const std::vector<std::string_view>& features, std::string_view feature)
	{
		return std::find(features.begin(), features.end(), feature) != features.end();
	}

	linux_hwcap_snapshot all_optional_features()
	{
		return {
			true,
			hwcap_atomics | hwcap_asimd_hp | hwcap_asimd_rdm | hwcap_asimd_dp | hwcap_asimd_fhm | hwcap_lrcpc | hwcap_sve,
			hwcap2_sve2 | hwcap2_i8mm | hwcap2_bf16,
		};
	}
}

int main()
{
	// Missing auxv data disables every optional feature, including scalable
	// vector state which LLVM may otherwise infer from a modern -mcpu.
	const auto no_auxv = disabled_llvm_features({false, ~std::uint64_t{0}, ~std::uint64_t{0}});
	assert(no_auxv.size() == 10);
	assert(contains(no_auxv, "-sve"));
	assert(contains(no_auxv, "-sve2"));
	assert(contains(no_auxv, "-fp16fml"));

	// SVE2 is dependent on SVE even if a malformed or synthetic auxv advertises
	// HWCAP2_SVE2 by itself.
	const auto sve2_without_sve = disabled_llvm_features({true, 0, hwcap2_sve2});
	assert(contains(sve2_without_sve, "-sve"));
	assert(contains(sve2_without_sve, "-sve2"));

	// Missing SVE2 keeps SVE usable but explicitly disables its dependent ISA.
	const auto sve_without_sve2 = disabled_llvm_features({true, hwcap_sve, 0});
	assert(!contains(sve_without_sve2, "-sve"));
	assert(contains(sve_without_sve2, "-sve2"));

	// FP16 FML depends on AdvSIMD FP16; advertising FHM without the prerequisite
	// must leave both LLVM features disabled.
	const auto fml_without_fullfp16 = disabled_llvm_features({true, hwcap_asimd_fhm, 0});
	assert(contains(fml_without_fullfp16, "-fullfp16"));
	assert(contains(fml_without_fullfp16, "-fp16fml"));

	// OnePlus 13R OS report captured by the parent capability probe: the
	// Advanced SIMD extensions are available, while SVE and SVE2 are not.
	const auto oneplus13r = disabled_llvm_features({true, 0xefbfffffull, 0x1ae181ull});
	assert(oneplus13r.size() == 2);
	assert(contains(oneplus13r, "-sve"));
	assert(contains(oneplus13r, "-sve2"));
	assert(!contains(oneplus13r, "-fp16fml"));
	assert(!contains(oneplus13r, "-dotprod"));
	assert(!contains(oneplus13r, "-i8mm"));
	assert(!contains(oneplus13r, "-bf16"));

	// Fully capable OS does not add positive attributes. An explicit older
	// -mcpu such as cortex-a34 therefore retains its own feature baseline.
	const auto older_cpu_with_capable_os = disabled_llvm_features(all_optional_features());
	assert(older_cpu_with_capable_os.empty());

	// The full CPU/OS intersection removes unavailable individual features and
	// preserves only those the kernel says are usable.
	const auto partial = disabled_llvm_features({true, hwcap_asimd_hp | hwcap_sve, 0});
	assert(contains(partial, "-lse"));
	assert(!contains(partial, "-fullfp16"));
	assert(contains(partial, "-fp16fml"));
	assert(contains(partial, "-dotprod"));
	assert(contains(partial, "-rdm"));
	assert(contains(partial, "-rcpc"));
	assert(contains(partial, "-bf16"));
	assert(contains(partial, "-i8mm"));
	assert(contains(partial, "-sve2"));
}
