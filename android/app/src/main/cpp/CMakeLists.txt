cmake_minimum_required(VERSION 3.22.1)
project(deckbridge-engine)
add_library(deckbridge-engine SHARED engine.cpp)
find_package(oboe REQUIRED CONFIG)
target_link_libraries(deckbridge-engine oboe::oboe android log)
target_compile_features(deckbridge-engine PRIVATE cxx_std_17)
# Estouro de inteiro com sinal definido (dá a volta): o dr_flac tem um sob UBSan com FLAC estragado (fuzz do Pi).
target_compile_options(deckbridge-engine PRIVATE -fwrapv)
