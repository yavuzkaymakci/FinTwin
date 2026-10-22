import { ChatPanel } from '@/components/chat/ChatPanel';
import { PageHeader } from '@/components/common';

const SUGGESTIONS = [
  '12 ayda 100.000 TL biriktirebilir miyim?',
  'Restoran harcamamı yarıya indirirsem tatil için 60.000 TL biriktirebilir miyim?',
  'Son ay paramı en çok nereye harcadım?',
  'Önümüzdeki aylarda bakiyem nasıl görünüyor?',
];

export function CoachPage() {
  return (
    <>
      <PageHeader title="Finans koçu" eyebrow="Kişisel"
        description="Birikim hedeflerinizi kendi harcama alışkanlıklarınızla simüle edin. Koç yatırım tavsiyesi vermez." />
      <ChatPanel kind="coach" title="Finans koçu" suggestions={SUGGESTIONS}
        intro={<>Koç yalnızca sizin hesap verinizi görür. Bir hedef söyleyin; ulaşma olasılığınızı ve gereken aylık
          birikimi hesaplasın.</>} />
    </>
  );
}
