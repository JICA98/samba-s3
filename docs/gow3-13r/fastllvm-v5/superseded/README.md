# Superseded experiments, preserved at v5 closure

These patches preserve the shared checkout before promoting the tested v5 source. They are **not applied** and must not be applied on top of the final core. The core prototype and Mega-to-Safe override were not part of the tested APK. Runtime code comes from the tested private build views; the parent engine patch remains comment-only because the engine changes are committed in its repository.

The original scratch assembler sources and patch helper remain under `scratch/`; they are exploratory, not build inputs. The generated `scratch/gate.o` remains local.
