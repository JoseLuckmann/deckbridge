// Entrada MIDI da DDJ-400 no Linux pelo ALSA rawmidi (classe USB MIDI, sem driver próprio). Uma thread lê os bytes,
// monta as mensagens (MidiAssembler) e entrega cada uma; a lógica de ação fica com quem chama (controller.h).
// O nome do aparelho vem de DECKBRIDGE_MIDI_DEVICE (ex.: "hw:2,0,0"); sem ela, o primeiro rawmidi de entrada cujo
// nome contém o filtro pedido ("DDJ" por padrão; vazio aceita qualquer um). Não supomos o nome que a DDJ-400 dá.
#pragma once

#include "controller.h"

#include <alsa/asoundlib.h>
#include <poll.h>

#include <atomic>
#include <functional>
#include <string>
#include <thread>
#include <vector>

namespace deckbridge::control {

struct RawMidiPort {
    std::string device;  // "hw:CARD,DEV,SUB"
    std::string name;
};

/** Portas rawmidi de entrada (ou de saída) que o ALSA conhece agora. */
inline std::vector<RawMidiPort> listRawMidiInputs(snd_rawmidi_stream_t stream = SND_RAWMIDI_STREAM_INPUT) {
    std::vector<RawMidiPort> ports;
    int card = -1;
    while (snd_card_next(&card) == 0 && card >= 0) {
        snd_ctl_t *ctl = nullptr;
        const std::string hw = "hw:" + std::to_string(card);
        if (snd_ctl_open(&ctl, hw.c_str(), 0) < 0) continue;
        int device = -1;
        while (snd_ctl_rawmidi_next_device(ctl, &device) == 0 && device >= 0) {
            snd_rawmidi_info_t *info = nullptr;
            snd_rawmidi_info_alloca(&info);
            snd_rawmidi_info_set_device(info, static_cast<unsigned>(device));
            snd_rawmidi_info_set_stream(info, stream);
            snd_rawmidi_info_set_subdevice(info, 0);
            if (snd_ctl_rawmidi_info(ctl, info) < 0) continue;
            ports.push_back({hw + "," + std::to_string(device) + ",0", snd_rawmidi_info_get_name(info)});
        }
        snd_ctl_close(ctl);
    }
    return ports;
}

class MidiIn {
public:
    using Handler = std::function<void(const MidiMessage &)>;

    ~MidiIn() { close(); }

    /** Abre e começa a ler. Devolve o nome do aparelho aberto, ou vazio se nenhum serviu. */
    std::string open(const std::string &filter, Handler handler) {
        close();
        std::string device;
        std::string name;
        const char *env = std::getenv("DECKBRIDGE_MIDI_DEVICE");
        if (env != nullptr && *env != '\0') {
            device = env;
            name = env;
        } else {
            for (const auto &port : listRawMidiInputs()) {
                if (filter.empty() || port.name.find(filter) != std::string::npos) {
                    device = port.device;
                    name = port.name;
                    break;
                }
            }
        }
        if (device.empty()) return "";
        if (snd_rawmidi_open(&in_, nullptr, device.c_str(), SND_RAWMIDI_NONBLOCK) < 0) {
            in_ = nullptr;
            return "";
        }
        handler_ = std::move(handler);
        running_.store(true);
        thread_ = std::thread([this]() { loop(); });
        return name + " (" + device + ")";
    }

    void close() {
        running_.store(false);
        if (thread_.joinable()) thread_.join();
        if (in_ != nullptr) {
            snd_rawmidi_close(in_);
            in_ = nullptr;
        }
    }

    /** O cabo saiu (leitura deu erro de aparelho): quem chama reabre quando a controladora voltar. */
    bool lost() const { return lost_.load(); }

private:
    void loop() {
        const int count = snd_rawmidi_poll_descriptors_count(in_);
        std::vector<pollfd> fds(static_cast<size_t>(std::max(1, count)));
        snd_rawmidi_poll_descriptors(in_, fds.data(), static_cast<unsigned>(fds.size()));
        uint8_t buffer[256];
        std::vector<MidiMessage> messages;
        messages.reserve(128);
        while (running_.load()) {
            // Acorda a cada 100 ms para ver se é para parar; com bytes chegando, acorda na hora.
            if (poll(fds.data(), fds.size(), 100) <= 0) continue;
            const ssize_t got = snd_rawmidi_read(in_, buffer, sizeof buffer);
            if (got == -EAGAIN) continue;
            if (got < 0) {
                lost_.store(true);
                break;
            }
            messages.clear();
            assembler_.push(buffer, static_cast<size_t>(got), messages);
            for (const auto &m : messages) handler_(m);
        }
    }

    snd_rawmidi_t *in_ = nullptr;
    Handler handler_;
    MidiAssembler assembler_;
    std::thread thread_;
    std::atomic<bool> running_{false};
    std::atomic<bool> lost_{false};
};

/** Saída MIDI para a controladora (LEDs). Mesmo critério de escolha da entrada; DECKBRIDGE_MIDI_OUT tem precedência. */
class MidiOut {
public:
    ~MidiOut() { close(); }

    std::string open(const std::string &filter) {
        close();
        std::string device;
        std::string name;
        const char *env = std::getenv("DECKBRIDGE_MIDI_OUT");
        if (env != nullptr && *env != '\0') {
            device = env;
            name = env;
        } else {
            for (const auto &port : listRawMidiInputs(SND_RAWMIDI_STREAM_OUTPUT)) {
                if (filter.empty() || port.name.find(filter) != std::string::npos) {
                    device = port.device;
                    name = port.name;
                    break;
                }
            }
        }
        if (device.empty() || snd_rawmidi_open(nullptr, &out_, device.c_str(), SND_RAWMIDI_NONBLOCK) < 0) {
            out_ = nullptr;
            return "";
        }
        return name + " (" + device + ")";
    }

    bool isOpen() const { return out_ != nullptr; }

    /** Escreve sem bloquear; o que não couber agora some (o próximo diff reenvia o estado que mudar). */
    void send(const std::vector<uint8_t> &bytes) {
        if (out_ == nullptr || bytes.empty()) return;
        if (snd_rawmidi_write(out_, bytes.data(), bytes.size()) < 0) {
            snd_rawmidi_close(out_);
            out_ = nullptr;
        }
    }

    void close() {
        if (out_ != nullptr) {
            snd_rawmidi_close(out_);
            out_ = nullptr;
        }
    }

private:
    snd_rawmidi_t *out_ = nullptr;
};

}  // namespace deckbridge::control
