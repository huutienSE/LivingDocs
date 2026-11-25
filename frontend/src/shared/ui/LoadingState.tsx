interface Props {
  message?: string;
}

export function LoadingState({ message = 'Đang tải…' }: Props) {
  return (
    <p role="status" aria-busy="true">
      {message}
    </p>
  );
}
