import { ChatPanel } from '@/components/chat/ChatPanel';
import { PageHeader } from '@/components/common';

const SUGGESTIONS = [
  'Hazır senaryoları listele',
  'Dolar %30 artarsa ve aylık enflasyon %4 olursa 12 ayda portföy ne olur?',
  'Kur şoku senaryosunu baz senaryoyla karşılaştır',
  'Hangi segment en kırılgan?',
  'Portföyün mevcut risk özetini ver',
  'Son koşuda en çok etkilenen müşteriler kimler?',
];

export function AssistantPage() {
  return (
    <>
      <PageHeader title="Senaryo asistanı" eyebrow="Yapay zekâ"
        description="Makro senaryoları doğal dille tanımlayın, simülasyonu onayınızla başlatın, sonuçları yorumlatın." />
      <ChatPanel kind="scenario" title="Senaryo asistanı" suggestions={SUGGESTIONS}
        intro={<>Asistan senaryo oluşturur, simülasyon çalıştırır ve sonuçları portföy verisiyle açıklar. Simülasyon
          başlatmadan önce her zaman onayınızı ister.</>} />
    </>
  );
}
