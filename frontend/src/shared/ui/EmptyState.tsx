interface Props {
  message?: string;
}

export function EmptyState({ message = 'Chưa có dữ liệu.' }: Props) {
  return <p className="empty">{message}</p>;
}
